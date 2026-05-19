package spec

import fabric.Json
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Task}
import sigil.TurnContext
import sigil.conversation.{Conversation, ConversationView, TopicEntry, TurnInput}
import sigil.event.Event
import sigil.provider.Complexity
import sigil.tooling.container.{CreateContainerInput, CreateContainerTool}
import sigil.tooling.dispatch.{
  DispatchWorkersInput, DispatchWorkersOutput, DispatchWorkersTool,
  LlmStep, WorkerPipeline
}

import scala.concurrent.duration.*

/**
 * Acceptance for the `dispatch_workers` empty-items guard. When
 * `itemsId` resolves to an empty container AND `confirmed: true`,
 * the tool returns a structured failure carrying a "nothing to
 * dispatch" hint rather than silently completing as if work
 * happened. When `confirmed: false` the same input returns a
 * scope preview with zero workers — no rejection.
 */
class DispatchWorkersEmptyItemsSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {

  DispatchTestSigil.initFor(getClass.getSimpleName)

  override implicit val testTimeout: FiniteDuration = 30.seconds

  private def turnContext(): TurnContext = {
    val convId = Conversation.id(s"empty-items-${rapid.Unique()}")
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

  private def emptyContainer(ctx: TurnContext): lightdb.id.Id[sigil.tool.output.ToolOutputNode] =
    CreateContainerTool.invoke(CreateContainerInput(items = List.empty[Json]), ctx).sync().itemsId

  "dispatch_workers" should {

    "reject empty items + confirmed=true with a structured 'nothing to dispatch' failure" in {
      DispatchTestSigil.reset()
      val tool = new DispatchWorkersTool(scriptExecutor = None)
      val ctx = turnContext()
      val input = DispatchWorkersInput(
        complexity = Complexity.Low,
        confirmed  = true,
        itemsId    = emptyContainer(ctx),
        pipeline   = WorkerPipeline(
          llm = Some(LlmStep(prompt = "irrelevant: {{item}}"))
        ),
        workerModelId = Some("stub-model")
      )
      tool.invoke(input, ctx).map {
        case d: DispatchWorkersOutput.DispatchResult =>
          d.totalItems shouldBe 0
          d.successCount shouldBe 0
          d.failureCount shouldBe 0
          d.results shouldBe empty
          val reason = d.abortReason.getOrElse(fail("expected an abortReason"))
          reason should include ("empty items list")
          reason should include ("confirmed=true")
          reason should include ("nothing to dispatch")
        case other => fail(s"expected DispatchResult, got $other")
      }
    }

    "return a scope preview (0 workers, no rejection) for empty items + confirmed=false" in {
      DispatchTestSigil.reset()
      val tool = new DispatchWorkersTool(scriptExecutor = None)
      val ctx = turnContext()
      val input = DispatchWorkersInput(
        complexity = Complexity.Low,
        confirmed  = false,
        itemsId    = emptyContainer(ctx),
        pipeline   = WorkerPipeline(
          llm = Some(LlmStep(prompt = "irrelevant: {{item}}"))
        ),
        workerModelId = Some("stub-model")
      )
      tool.invoke(input, ctx).map {
        case s: DispatchWorkersOutput.ScopePreview =>
          s.totalItems shouldBe 0
          s.perItemSample shouldBe empty
          // No abort reason — the scope preview is a successful response,
          // not a rejection. The agent reads "0 workers" and decides.
          s.abortReason shouldBe None
          s.resolvedModelId shouldBe "stub-model"
        case other => fail(s"expected ScopePreview, got $other")
      }
    }
  }
}
