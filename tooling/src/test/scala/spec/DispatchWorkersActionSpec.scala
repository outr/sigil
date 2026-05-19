package spec

import fabric.{Json, Obj, Str}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Task}
import sigil.TurnContext
import sigil.conversation.{Conversation, ConversationView, TopicEntry, TurnInput}
import sigil.event.Event
import sigil.script.{CompileError, CompiledScript, ScalaScriptExecutor, ScriptBinding, ScriptExecutor}
import sigil.tool.output.ToolOutputNode
import sigil.tooling.container.{CreateContainerInput, CreateContainerTool}
import sigil.tooling.dispatch.{DispatchWorkersInput, DispatchWorkersOutput, DispatchWorkersTool}

import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.duration.*

/**
 * Acceptance for the `dispatch_workers` adhoc-`action` shape (bug
 * #245). The dispatcher compiles the action once via the real
 * `ScalaScriptExecutor`, then runs N workers in parallel against the
 * shared compiled artifact.
 *
 * Five cases:
 *  1. A non-compiling action with `confirmed=true` returns a
 *     `CompileFailure` with typed errors; NO worker runs.
 *  2. A valid action with `confirmed=false` returns a `ScopePreview`
 *     (`compileOk = true`); NO worker runs.
 *  3. A valid action with `confirmed=true` runs once per item and
 *     captures each worker's return value.
 *  4. The compiled artifact is shared — `compile` is called exactly
 *     once across a 100-item, `maxParallel=10` dispatch.
 *  5. A runtime error in one worker is isolated — other workers
 *     still produce `Right` outcomes.
 */
class DispatchWorkersActionSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {

  DispatchTestSigil.initFor(getClass.getSimpleName)

  override implicit val testTimeout: FiniteDuration = 120.seconds

  private def turnContext(): TurnContext = {
    val convId = Conversation.id(s"dispatch-action-${rapid.Unique()}")
    val conv = Conversation(
      topics = List(TopicEntry(DispatchTestTopicId, "test", "test")),
      _id    = convId
    )
    DispatchTestSigil.withDB(_.conversations.transaction(_.upsert(conv))).sync()
    TurnContext(
      sigil               = DispatchTestSigil,
      chain               = List(DispatchTestUser),
      conversation        = conv,
      turnInput           = TurnInput(ConversationView(conversationId = convId)),
      currentToolInvokeId = Some(Event.id())
    )
  }

  private def containerFor(items: List[Json], ctx: TurnContext): lightdb.id.Id[ToolOutputNode] =
    CreateContainerTool.invoke(CreateContainerInput(items), ctx).sync().itemsId

  "dispatch_workers action pre-flight compile" should {

    // Case 1
    "return a CompileFailure (and dispatch no worker) for a non-compiling action" in {
      DispatchTestSigil.reset()
      val executor = new CountingExecutor(new ScalaScriptExecutor())
      val tool = new DispatchWorkersTool(scriptExecutor = Some(executor))
      val ctx = turnContext()
      val items: List[Json] = (1 to 5).toList.map(i => Obj("name" -> Str(s"item-$i")))
      val input = DispatchWorkersInput(
        itemsId   = containerFor(items, ctx),
        action    = "this is not valid scala",
        confirmed = true
      )
      tool.invoke(input, ctx).map {
        case f: DispatchWorkersOutput.CompileFailure =>
          f.errors should not be empty
          all(f.errors.map(_.message.nonEmpty)) shouldBe true
          // A compile failure means no compiled artifact and no
          // worker invocation.
          executor.invokeCount.get shouldBe 0
        case other => fail(s"expected CompileFailure, got $other")
      }
    }
  }

  "dispatch_workers action confirmed=false" should {

    // Case 2
    "return a ScopePreview (compileOk=true, no workers) after a valid action compiles" in {
      DispatchTestSigil.reset()
      val executor = new CountingExecutor(new ScalaScriptExecutor())
      val tool = new DispatchWorkersTool(scriptExecutor = Some(executor))
      val ctx = turnContext()
      val items: List[Json] = (1 to 5).toList.map(i => Obj("name" -> Str(s"item-$i")))
      val input = DispatchWorkersInput(
        itemsId   = containerFor(items, ctx),
        action    = "fabric.Str(items.head(\"name\").asString.toUpperCase)",
        confirmed = false
      )
      tool.invoke(input, ctx).map {
        case s: DispatchWorkersOutput.ScopePreview =>
          s.compileOk shouldBe true
          s.totalItems shouldBe 5
          s.actionPreview should not be empty
          executor.invokeCount.get shouldBe 0
        case f: DispatchWorkersOutput.CompileFailure =>
          fail(s"expected ScopePreview, got CompileFailure(${f.errors})")
        case other => fail(s"expected ScopePreview, got $other")
      }
    }
  }

  "dispatch_workers action confirmed=true" should {

    // Case 3
    "run the action once per item with the item bound" in {
      DispatchTestSigil.reset()
      val tool = new DispatchWorkersTool(scriptExecutor = Some(new ScalaScriptExecutor()))
      val ctx = turnContext()
      val names = List("alpha", "beta", "gamma", "delta", "epsilon")
      val items: List[Json] = names.map(n => Obj("name" -> Str(n)))
      val input = DispatchWorkersInput(
        itemsId   = containerFor(items, ctx),
        action    = "fabric.Str(items.head(\"name\").asString.toUpperCase)",
        confirmed = true
      )
      tool.invoke(input, ctx).map {
        case d: DispatchWorkersOutput.DispatchResult =>
          withClue(s"outcomes: ${d.perItem.mkString("\n")}\n") {
            d.totalItems shouldBe 5
            d.perItem.size shouldBe 5
            d.perItem.map(_.result) shouldBe names.map(n => Right(Str(n.toUpperCase)))
          }
        case other => fail(s"expected DispatchResult, got $other")
      }
    }
  }

  "dispatch_workers shared compiled artifact" should {

    // Case 4
    "compile the action exactly once across a 100-item, maxParallel=10 dispatch" in {
      DispatchTestSigil.reset()
      val executor = new CountingExecutor(new ScalaScriptExecutor())
      val tool = new DispatchWorkersTool(scriptExecutor = Some(executor))
      val ctx = turnContext()
      val items: List[Json] = (1 to 100).toList.map(i => Obj("name" -> Str(s"item-$i")))
      val input = DispatchWorkersInput(
        itemsId     = containerFor(items, ctx),
        action      = "items.head(\"name\")",
        confirmed   = true,
        maxParallel = 10
      )
      tool.invoke(input, ctx).map {
        case d: DispatchWorkersOutput.DispatchResult =>
          d.totalItems shouldBe 100
          d.successCount shouldBe 100
          // The heavy compile happened once; every worker reused the
          // shared artifact.
          executor.compileCount.get shouldBe 1
          executor.invokeCount.get shouldBe 100
        case other => fail(s"expected DispatchResult, got $other")
      }
    }
  }

  "dispatch_workers runtime error isolation" should {

    // Case 5
    "isolate a runtime throw in one worker — the others still succeed" in {
      DispatchTestSigil.reset()
      val tool = new DispatchWorkersTool(scriptExecutor = Some(new ScalaScriptExecutor()))
      val ctx = turnContext()
      val names = List("ok-1", "ok-2", "boom", "ok-3", "ok-4")
      val items: List[Json] = names.map(n => Obj("name" -> Str(n)))
      val action =
        """val name = items.head("name").asString
          |if (name == "boom") throw new RuntimeException("explode")
          |else fabric.Str(name)""".stripMargin
      val input = DispatchWorkersInput(
        itemsId   = containerFor(items, ctx),
        action    = action,
        confirmed = true
      )
      tool.invoke(input, ctx).map {
        case d: DispatchWorkersOutput.DispatchResult =>
          withClue(s"outcomes: ${d.perItem.mkString("\n")}\n") {
            d.totalItems shouldBe 5
            d.perItem.count(_.result.isRight) shouldBe 4
            d.perItem.count(_.result.isLeft) shouldBe 1
            val failure = d.perItem.find(_.result.isLeft).getOrElse(fail("expected one Left outcome"))
            failure.result.left.toOption.getOrElse("") should include ("explode")
          }
        case other => fail(s"expected DispatchResult, got $other")
      }
    }
  }

  "tear down" should {
    "dispose DispatchTestSigil" in DispatchTestSigil.shutdown.map(_ => succeed)
  }
}

/**
 * A [[ScriptExecutor]] that delegates to a real executor while
 * counting `compile` and per-worker `invoke` calls. Counters use
 * `AtomicInteger` so parallel worker fibers can't drop an increment.
 */
class CountingExecutor(delegate: ScriptExecutor) extends ScriptExecutor {

  val compileCount: AtomicInteger = new AtomicInteger(0)
  val invokeCount: AtomicInteger = new AtomicInteger(0)

  override def execute(code: String, bindings: Map[String, Any]): Task[String] =
    delegate.execute(code, bindings)

  override def compile(source: String,
                       bindingTypes: List[ScriptBinding]): Task[Either[List[CompileError], CompiledScript]] = {
    compileCount.incrementAndGet()
    delegate.compile(source, bindingTypes).map {
      case Left(errors)    => Left(errors)
      case Right(compiled) => Right(new CountingCompiledScript(compiled, invokeCount))
    }
  }
}

/** Wraps a [[CompiledScript]] to count every [[invoke]]. */
class CountingCompiledScript(delegate: CompiledScript, counter: AtomicInteger) extends CompiledScript {
  override def invoke(bindings: Map[String, Any]): Task[Either[String, Json]] = {
    counter.incrementAndGet()
    delegate.invoke(bindings)
  }
}
