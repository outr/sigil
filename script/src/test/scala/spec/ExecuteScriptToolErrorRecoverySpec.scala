package spec

import fabric.*
import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Stream, Task}
import sigil.{GlobalSpace, TurnContext}
import sigil.conversation.{Conversation, Topic, TopicEntry, TurnInput}
import sigil.event.{Event, ToolOutcome}
import sigil.participant.ParticipantId
import sigil.script.{ExecuteScriptTool, ScriptInput, ScriptTool, ScriptToolOutput, ScriptExecutor}
import sigil.signal.{Signal, ToolDelta}
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
 *     plus an outer `.handleError` so even synchronous throws are
 *     caught and the call still settles with a paired result.
 *   - A compile / runtime error now settles as a recoverable
 *     [[sigil.tool.ToolResult.Failure]] — the framework renders it as
 *     a settling `ToolDelta` whose `outcome` is
 *     [[ToolOutcome.Failure]] carrying the error text (with an
 *     abbreviated stack trace, not just `getMessage`). The agent reads
 *     it as a recoverable failure rather than a success-shaped result.
 *   - The `Provider.scala` placeholder is reworded to be diagnostic
 *     rather than blandly true.
 *
 * The tests below use a fault-injecting [[ScriptExecutor]] to
 * exercise both the deferred-Task error path and the synchronous
 * throw-during-construction path.
 */
class ExecuteScriptToolErrorRecoverySpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestScriptSigil.initFor(getClass.getSimpleName)

  /**
   * Executor whose `execute` returns a Task that fails — the
   * standard "script threw a runtime exception" case.
   */
  private object FailingExecutor extends ScriptExecutor {
    override def execute(code: String, bindings: Map[String, Any]): Task[String] =
      Task.error(new RuntimeException("synthetic script failure"))
    override def executeRaw(code: String, bindings: Map[String, Any]): Task[Any] =
      Task.error(new RuntimeException("synthetic script failure"))
    override def preludeImports: List[String] = Nil
    override def advertisedSurface: Option[String] = None
  }

  /**
   * Executor whose `execute` THROWS synchronously before returning a
   * Task. Covers the case where the executor's argument-binding /
   * classloader-resolution path fails before reaching deferred Task
   * machinery. Pre-fix, the throw escaped `Stream.force` and the
   * orchestrator delivered `(no result recorded)`; post-fix the
   * outer `handleError` catches it.
   */
  private object SyncThrowExecutor extends ScriptExecutor {
    override def execute(code: String, bindings: Map[String, Any]): Task[String] =
      throw new RuntimeException("synthetic synchronous throw before Task construction")
    override def executeRaw(code: String, bindings: Map[String, Any]): Task[Any] =
      throw new RuntimeException("synthetic synchronous throw before Task construction")
    override def preludeImports: List[String] = Nil
    override def advertisedSurface: Option[String] = None
  }

  /**
   * Executor whose `execute` returns success — sanity check that the
   * happy path still produces exactly one ScriptResult with output.
   */
  private object SucceedingExecutor extends ScriptExecutor {
    override def execute(code: String, bindings: Map[String, Any]): Task[String] =
      Task.pure(s"ran:$code")
    override def executeRaw(code: String, bindings: Map[String, Any]): Task[Any] =
      Task.pure(s"ran:$code")
    override def preludeImports: List[String] = Nil
    override def advertisedSurface: Option[String] = None
  }

  /**
   * Pull the `ScriptToolOutput` off the settling Success ToolDelta.
   */
  private def scriptOutput(signals: List[Signal]): ScriptToolOutput =
    signals.collectFirst {
      case d: ToolDelta if d.outcome.contains(ToolOutcome.Success) =>
        d.output.collect { case o: ScriptToolOutput => o }
    }.flatten
      .getOrElse(fail(
        s"expected a settling Success ToolDelta carrying ScriptToolOutput; saw: ${signals.map(_.getClass.getSimpleName).mkString(", ")}"))

  /**
   * Pull the error text off the settling recoverable-Failure ToolDelta.
   */
  private def scriptFailureBody(signals: List[Signal]): String =
    signals.iterator.collect { case d: ToolDelta => d.outcome }.flatten.collectFirst {
      case ToolOutcome.Failure(reason, _) => reason
    }.getOrElse(fail(s"expected a settling Failure ToolDelta; saw: ${signals.map(_.getClass.getSimpleName).mkString(", ")}"))

  private def ctx(suffix: String): TurnContext = {
    val convId = Conversation.id(s"recover-$suffix-${rapid.Unique()}")
    val topic = Topic(conversationId = convId, label = "Recovery", summary = "Test", createdBy = TestScriptUser)
    TurnContext(
      sigil = TestScriptSigil,
      chain = List(TestScriptUser, TestScriptAgent),
      conversation = Conversation(
        topics = List(TopicEntry(topic._id, topic.label, topic.summary)),
        _id = convId
      ),
      turnInput = TurnInput(conversationId = convId),
      model = TestSigil.defaultTestModel
    )
  }

  "ExecuteScriptTool (bug #67)" should {
    "settle a recoverable Failure carrying the error when the executor's Task fails" in {
      val tool = new ExecuteScriptTool(FailingExecutor)
      tool.execute(ScriptInput(code = "anything", summary = "test: error path"), ctx("task-failure"), Event.id()).toList.map { signals =>
        val body = scriptFailureBody(signals)
        body should include("RuntimeException")
        body should include("synthetic script failure")
      }
    }

    "settle a recoverable Failure when the executor THROWS synchronously" in {
      // Pre-fix: this case would emit no events (sync throw escaped
      // Stream.force) and the orchestrator's dangling-tool-call
      // fallback would later inject `(no result recorded)`.
      // Post-fix: the outer Task.defer + handleError catches the
      // throw and the call settles with a paired recoverable Failure.
      val tool = new ExecuteScriptTool(SyncThrowExecutor)
      tool.execute(ScriptInput(code = "anything", summary = "test: error path"), ctx("sync-throw"), Event.id()).toList.map { signals =>
        val body = scriptFailureBody(signals)
        body should include("RuntimeException")
        body should include("synthetic synchronous throw")
      }
    }

    "emit a ScriptToolOutput with `output` populated on the happy path" in {
      val tool = new ExecuteScriptTool(SucceedingExecutor)
      tool.execute(ScriptInput(code = "1 + 2", summary = "test: happy path"), ctx("happy"), Event.id()).toList.map { signals =>
        val out = scriptOutput(signals)
        out.output shouldBe Some("ran:1 + 2")
        out.error shouldBe empty
      }
    }

    "format the failure with stack-trace context, not just getMessage" in {
      // Assertion checks that the formatted error includes "at "
      // (a stack-frame line marker) — the post-fix `formatThrowable`
      // takes the first 8 lines of `printStackTrace`, which always
      // starts with the throwable line followed by `at` frames.
      val tool = new ExecuteScriptTool(FailingExecutor)
      tool.execute(ScriptInput(code = "x", summary = "test: stack-trace path"), ctx("stack-trace"), Event.id()).toList.map { signals =>
        scriptFailureBody(signals) should include("at ")
      }
    }
  }

  "ScriptTool (bug #67) — same recovery in the persisted-tool path" should {
    "settle a recoverable Failure carrying the error when the script body throws" in {
      val tool = ScriptTool(
        name = ToolName("recover-test-throws"),
        description = "A script that throws a runtime exception.",
        code = """throw new RuntimeException("script body throw")""",
        parameters = JsonSchemaToDefinition(obj("type" -> str("object"))),
        space = GlobalSpace
      )
      tool.execute(JsonInput(obj()), ctx("script-tool-throw"), Event.id()).toList.map { signals =>
        val body = scriptFailureBody(signals)
        withClue(s"got failure body=$body: ") {
          body should include("Exception")
          body should include("at ")
        }
      }
    }
  }

  "tear down" should {
    "dispose TestScriptSigil" in TestScriptSigil.shutdown.map(_ => succeed)
  }
}
