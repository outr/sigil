package spec

import fabric.*
import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Stream, Task}
import sigil.{GlobalSpace, TurnContext}
import sigil.conversation.{Conversation, ConversationView, Topic, TopicEntry, TurnInput}
import sigil.event.Event
import sigil.participant.ParticipantId
import sigil.script.{ExecuteScriptTool, ScriptInput, ScriptResult, ScriptTool, ScriptExecutor}
import sigil.tool.{JsonInput, JsonSchemaToDefinition, ToolName}

/**
 * Regression coverage for bug #67 — `ExecuteScriptTool` and
 * `ScriptTool` used to be susceptible to losing their tool result
 * entirely. Synchronous throws during stream construction
 * (`bindings(context)` evaluation, an executor implementation that
 * throws before returning a Task) escaped the inner `handleError`;
 * the orchestrator saw no `MessageRole.Tool` event for the call_id;
 * `Provider.scala`'s dangling-tool-call fallback delivered the
 * unhelpful `(no result recorded)` placeholder.
 *
 * Post-fix:
 *   - Both tools wrap stream construction in `Task.defer { … }`
 *     plus an outer `.handleError` so even synchronous throws become
 *     a populated `ScriptResult.error`.
 *   - The error string carries an abbreviated stack trace, not just
 *     `getMessage` — wrapped exceptions surface their root cause.
 *   - The `Provider.scala` placeholder is reworded to be diagnostic
 *     rather than blandly true.
 *
 * The tests below use a fault-injecting [[ScriptExecutor]] to
 * exercise both the deferred-Task error path and the synchronous
 * throw-during-construction path.
 */
class ExecuteScriptToolErrorRecoverySpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestScriptSigil.initFor(getClass.getSimpleName)

  /** Executor whose `execute` returns a Task that fails — the
    * standard "script threw a runtime exception" case. */
  private object FailingExecutor extends ScriptExecutor {
    override def execute(code: String, bindings: Map[String, Any]): Task[String] =
      Task.error(new RuntimeException("synthetic script failure"))
    override def executeRaw(code: String, bindings: Map[String, Any]): Task[Any] =
      Task.error(new RuntimeException("synthetic script failure"))
    override def preludeImports: List[String] = Nil
    override def advertisedSurface: Option[String] = None
  }

  /** Executor whose `execute` THROWS synchronously before returning a
    * Task. Covers the case where the executor's argument-binding /
    * classloader-resolution path fails before reaching deferred Task
    * machinery. Pre-fix, the throw escaped `Stream.force` and the
    * orchestrator delivered `(no result recorded)`; post-fix the
    * outer `handleError` catches it. */
  private object SyncThrowExecutor extends ScriptExecutor {
    override def execute(code: String, bindings: Map[String, Any]): Task[String] =
      throw new RuntimeException("synthetic synchronous throw before Task construction")
    override def executeRaw(code: String, bindings: Map[String, Any]): Task[Any] =
      throw new RuntimeException("synthetic synchronous throw before Task construction")
    override def preludeImports: List[String] = Nil
    override def advertisedSurface: Option[String] = None
  }

  /** Executor whose `execute` returns success — sanity check that the
    * happy path still produces exactly one ScriptResult with output. */
  private object SucceedingExecutor extends ScriptExecutor {
    override def execute(code: String, bindings: Map[String, Any]): Task[String] =
      Task.pure(s"ran:$code")
    override def executeRaw(code: String, bindings: Map[String, Any]): Task[Any] =
      Task.pure(s"ran:$code")
    override def preludeImports: List[String] = Nil
    override def advertisedSurface: Option[String] = None
  }

  private def ctx(suffix: String): TurnContext = {
    val convId = Conversation.id(s"recover-$suffix-${rapid.Unique()}")
    val topic = Topic(conversationId = convId, label = "Recovery", summary = "Test", createdBy = TestScriptUser)
    val view = ConversationView(conversationId = convId, _id = ConversationView.idFor(convId))
    TurnContext(
      sigil = TestScriptSigil,
      chain = List(TestScriptUser, TestScriptAgent),
      conversation = Conversation(
        topics = List(TopicEntry(topic._id, topic.label, topic.summary)),
        _id = convId
      ),
      conversationView = view,
      turnInput = TurnInput(view)
    )
  }

  "ExecuteScriptTool (bug #67)" should {
    "emit a ScriptResult with `error` populated when the executor's Task fails" in {
      val tool = new ExecuteScriptTool(FailingExecutor)
      tool.execute(ScriptInput(code = "anything"), ctx("task-failure")).toList.map { events =>
        val results = events.collect { case r: ScriptResult => r }
        results should have size 1
        val r = results.head
        r.error shouldBe defined
        // Error carries the throwable's class name (full stack-trace formatting).
        r.error.get should include ("RuntimeException")
        r.error.get should include ("synthetic script failure")
        r.output shouldBe None
      }
    }

    "emit a ScriptResult with `error` populated when the executor THROWS synchronously" in {
      // Pre-fix: this case would emit no events (sync throw escaped
      // Stream.force) and the orchestrator's dangling-tool-call
      // fallback would later inject `(no result recorded)`.
      // Post-fix: the outer Task.defer + handleError catches the
      // throw and emits a populated ScriptResult.
      val tool = new ExecuteScriptTool(SyncThrowExecutor)
      tool.execute(ScriptInput(code = "anything"), ctx("sync-throw")).toList.map { events =>
        val results = events.collect { case r: ScriptResult => r }
        results should have size 1
        val r = results.head
        r.error shouldBe defined
        r.error.get should include ("RuntimeException")
        r.error.get should include ("synthetic synchronous throw")
        r.output shouldBe None
      }
    }

    "emit a ScriptResult with `output` populated on the happy path" in {
      val tool = new ExecuteScriptTool(SucceedingExecutor)
      tool.execute(ScriptInput(code = "1 + 2"), ctx("happy")).toList.map { events =>
        val results = events.collect { case r: ScriptResult => r }
        results should have size 1
        val r = results.head
        r.output shouldBe Some("ran:1 + 2")
        r.error shouldBe None
      }
    }

    "format the error with stack-trace context, not just getMessage" in {
      // Assertion checks that the formatted error includes "at "
      // (a stack-frame line marker) — the post-fix `formatThrowable`
      // takes the first 8 lines of `printStackTrace`, which always
      // starts with the throwable line followed by `at` frames.
      val tool = new ExecuteScriptTool(FailingExecutor)
      tool.execute(ScriptInput(code = "x"), ctx("stack-trace")).toList.map { events =>
        val r = events.collectFirst { case r: ScriptResult => r }.get
        r.error.get should include ("at ")
      }
    }
  }

  "ScriptTool (bug #67) — same recovery in the persisted-tool path" should {
    "emit a ScriptResult with `error` populated when the executor's Task fails" in {
      // Build a ScriptTool whose execution path goes through
      // ScriptSigil.scriptExecutor — TestScriptSigil's executor is
      // ScalaScriptExecutor by default; we need to pin a failing one
      // for this test only. The simplest seam: use a tool with
      // `name`/`code` and rely on the standard ScalaScriptExecutor
      // failing on a script that throws.
      val tool = ScriptTool(
        name = ToolName("recover-test-throws"),
        description = "A script that throws a runtime exception.",
        code = """throw new RuntimeException("script body throw")""",
        parameters = JsonSchemaToDefinition(obj("type" -> str("object"))),
        space = GlobalSpace
      )
      tool.execute(JsonInput(obj()), ctx("script-tool-throw")).toList.map { events =>
        val results = events.collect { case r: ScriptResult => r }
        results should have size 1
        val r = results.head
        withClue(s"got error=${r.error}, output=${r.output}: ") {
          r.error shouldBe defined
          // The error string contains an abbreviated stack trace —
          // assert it's framing-shaped (throwable line + at-frames),
          // not which specific exception type the Scala REPL chose to
          // wrap the script's throw in. `InvocationTargetException`,
          // `RuntimeException`, `ScriptCompileException` all
          // legitimate depending on REPL phase + reflection
          // plumbing.
          r.error.get should include ("Exception")
          r.error.get should include ("at ")
          r.output shouldBe None
        }
      }
    }
  }

  "tear down" should {
    "dispose TestScriptSigil" in TestScriptSigil.shutdown.map(_ => succeed)
  }
}
