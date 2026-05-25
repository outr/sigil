package spec

import fabric.io.JsonFormatter
import fabric.rw.*
import fabric.{Json, Str}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Task}
import sigil.TurnContext
import sigil.conversation.{Conversation, ConversationView, TopicEntry, TurnInput}
import sigil.event.Event
import sigil.script.ScalaScriptExecutor
import sigil.tooling.container.{CreateContainerInput, CreateContainerTool}
import sigil.tooling.dispatch.{DispatchWorkersInput, DispatchWorkersOutput, DispatchWorkersTool}

import scala.concurrent.duration.*
import sigil.tool.ToolContext

/**
 * Acceptance for the `dispatch_workers(confirmed=false)` ScopePreview
 * shape — four cases:
 *
 *  1. `confirmed=false` returns a `ScopePreview`, NOT a
 *     `DispatchResult`; NO worker runs.
 *  2. ScopePreview includes `totalItems`, `workerCount`,
 *     `compileOk = true`, and a `confirmCall` text containing the
 *     literal `confirmed=true`.
 *  3. ScopePreview body stays under 8192 bytes (the framework's
 *     inline-truncation threshold) even for 1000-item containers.
 *  4. `confirmed=true` proceeds to actual dispatch (returns
 *     `DispatchResult`; workers ran).
 */
class DispatchWorkersScopePreviewSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {

  DispatchTestSigil.initFor(getClass.getSimpleName)

  override implicit val testTimeout: FiniteDuration = 60.seconds

  private val executor = new ScalaScriptExecutor()

  private def turnContext(): TurnContext = {
    val convId = Conversation.id(s"scope-preview-${rapid.Unique()}")
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
      model = TestSigil.defaultTestModel
    )
  }

  private def containerFor(items: List[Json], ctx: TurnContext): lightdb.id.Id[sigil.tool.output.ToolOutputNode] =
    CreateContainerTool.invoke(CreateContainerInput(items), ToolContext(ctx, Event.id(), CreateContainerTool.name)).sync().itemsId

  "dispatch_workers(confirmed=false)" should {

    // Case 1
    "return a ScopePreview (not a DispatchResult) with no worker runs" in {
      DispatchTestSigil.reset()
      val tool = new DispatchWorkersTool(scriptExecutor = Some(executor))
      val ctx = turnContext()
      val items: List[Json] = (1 to 47).toList.map(i => Str(s"item-$i"))
      val input = DispatchWorkersInput(
        itemsId   = containerFor(items, ctx),
        action    = "items.headOption",
        confirmed = false
      )
      tool.invoke(input, ToolContext(ctx, Event.id(), tool.name)).map { result =>
        result shouldBe a [DispatchWorkersOutput.ScopePreview]
        result shouldNot be (a [DispatchWorkersOutput.DispatchResult])
      }
    }

    // Case 2
    "include totalItems, workerCount, compileOk, and a confirmCall referencing confirmed=true" in {
      DispatchTestSigil.reset()
      val tool = new DispatchWorkersTool(scriptExecutor = Some(executor))
      val ctx = turnContext()
      val items: List[Json] = (1 to 47).toList.map(i => Str(s"item-$i"))
      val input = DispatchWorkersInput(
        itemsId   = containerFor(items, ctx),
        action    = "items.headOption",
        confirmed = false
      )
      tool.invoke(input, ToolContext(ctx, Event.id(), tool.name)).map {
        case s: DispatchWorkersOutput.ScopePreview =>
          s.totalItems shouldBe 47
          s.workerCount shouldBe 47
          s.compileOk shouldBe true
          s.actionPreview should not be empty
          s.confirmCall should include ("confirmed=true")
          s.sessionId should not be empty
        case other => fail(s"expected ScopePreview, got $other")
      }
    }

    // Case 3
    "stay under 8192 bytes even for 1000-item containers" in {
      DispatchTestSigil.reset()
      val tool = new DispatchWorkersTool(scriptExecutor = Some(executor))
      val ctx = turnContext()
      val items: List[Json] = (1 to 1000).toList.map { i =>
        Str(s"item-$i-with-a-reasonably-long-payload-to-stress-the-preview-size-cap")
      }
      val input = DispatchWorkersInput(
        itemsId   = containerFor(items, ctx),
        action    = "items.headOption",
        confirmed = false
      )
      tool.invoke(input, ToolContext(ctx, Event.id(), tool.name)).map {
        case s: DispatchWorkersOutput.ScopePreview =>
          val rendered = JsonFormatter.Compact(summon[RW[DispatchWorkersOutput]].read(s))
          rendered.length should be < 8192
          // Sanity-check: the sample is capped at ten items, not
          // the full thousand.
          s.perItemSample.size should be <= 10
          s.totalItems shouldBe 1000
        case other => fail(s"expected ScopePreview, got $other")
      }
    }
  }

  "dispatch_workers(confirmed=true)" should {

    // Case 4
    "proceed to actual dispatch (return DispatchResult; workers ran)" in {
      DispatchTestSigil.reset()
      val tool = new DispatchWorkersTool(scriptExecutor = Some(executor))
      val ctx = turnContext()
      val items: List[Json] = (1 to 3).toList.map(i => Str(s"item-$i"))
      val input = DispatchWorkersInput(
        itemsId   = containerFor(items, ctx),
        action    = "items.head",
        confirmed = true
      )
      tool.invoke(input, ToolContext(ctx, Event.id(), tool.name)).map {
        case d: DispatchWorkersOutput.DispatchResult =>
          d.totalItems shouldBe 3
          d.successCount shouldBe 3
          d.perItem.size shouldBe 3
        case other => fail(s"expected DispatchResult, got $other")
      }
    }
  }

  "tear down" should {
    "dispose DispatchTestSigil" in DispatchTestSigil.shutdown.map(_ => succeed)
  }
}
