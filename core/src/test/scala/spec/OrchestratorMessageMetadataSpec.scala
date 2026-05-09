package spec

import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Stream, Task}
import sigil.conversation.{Conversation, TurnInput}
import sigil.db.Model
import sigil.event.Message
import sigil.orchestrator.Orchestrator
import sigil.provider.{
  CallId, ConversationMode, ConversationRequest, GenerationSettings,
  Instructions, Provider, ProviderCall, ProviderEvent, ProviderType, StopReason, TokenUsage
}
import sigil.signal.{MessageDelta, Signal}
import sigil.tool.core.RespondTool
import sigil.tool.model.RespondInput
import spice.http.HttpRequest

/**
 * Coverage for sigil bug #55 — the agent's user-visible Messages
 * built by atomic content tools (`respond`, `respond_options`, …)
 * carry `modelId` and a per-turn `usage` MessageDelta even when the
 * provider is a tool-call-only model that never emits
 * `ContentBlockDelta`.
 *
 * The previously broken path: llama.cpp-style providers stream tool
 * args directly (no ContentBlockStart/Delta), so the orchestrator's
 * streaming-Message-creation path never fires. The atomic tool's
 * `executeTyped` builds a Complete-state Message, but it had no
 * access to the request's `modelId` and the orchestrator's `Usage`
 * handler had no `activeMessageId` to target.
 *
 * Fix: TurnContext carries `modelId`; respond-family tools stamp it
 * onto their Messages; the orchestrator records the last
 * user-visible Message id from atomic emissions and uses it as the
 * fallback target for `Usage` MessageDeltas.
 */
class OrchestratorMessageMetadataSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  private val modelId: Id[Model] = Model.id("test", "model-55")

  /** Tool-call-only provider: emits ToolCallStart → ToolCallComplete
    * → Usage → Done with no ContentBlockDelta. Mirrors the llama.cpp
    * grammar-constrained `respond` path. */
  private class ToolCallOnlyRespondProvider extends Provider {
    override def `type`: ProviderType = ProviderType.LlamaCpp
    override def models: List[_root_.sigil.db.Model] = Nil
    override protected def sigil: _root_.sigil.Sigil = TestSigil
    override def httpRequestFor(input: ProviderCall): Task[HttpRequest] =
      Task.error(new UnsupportedOperationException("no wire"))
    override def call(input: ProviderCall): Stream[ProviderEvent] = {
      val callId = CallId("respond-only")
      Stream.emits(List(
        ProviderEvent.ToolCallStart(callId, RespondTool.schema.name.value),
        ProviderEvent.ToolCallComplete(
          callId,
          RespondInput(topicLabel = "Test", topicSummary = "Bug 55 repro", content = "Hi.", endsTurn = true)
        ),
        ProviderEvent.Usage(TokenUsage(promptTokens = 4622, completionTokens = 46, totalTokens = 4668)),
        ProviderEvent.Done(StopReason.Complete)
      ))
    }
  }

  private def run(): Task[List[Signal]] = {
    val convId = Conversation.id("orchestrator-bug55")
    val conv   = Conversation(topics = TestTopicStack, _id = convId)
    val request = ConversationRequest(
      conversationId     = convId,
      modelId            = modelId,
      instructions       = Instructions(),
      turnInput          = TurnInput(conversationId = convId),
      currentMode        = ConversationMode,
      currentTopic       = TestTopicEntry,
      previousTopics     = Nil,
      generationSettings = GenerationSettings(maxOutputTokens = Some(50), temperature = Some(0.0)),
      chain              = List(TestUser, TestAgent),
      tools              = Vector(RespondTool)
    )
    for {
      _       <- TestSigil.withDB(_.conversations.transaction(_.upsert(conv)))
      signals <- Orchestrator.process(TestSigil, new ToolCallOnlyRespondProvider, request, conv).toList
    } yield signals
  }

  "Orchestrator (bug #55)" should {
    "stamp modelId on the agent's Message even when the provider only emits tool_calls (no ContentBlockDelta)" in {
      run().map { signals =>
        val agentMessages = signals.collect {
          case m: Message if m.participantId == TestAgent => m
        }
        agentMessages should have size 1
        agentMessages.head.modelId shouldBe Some(modelId)
      }
    }

    "emit a usage MessageDelta targeting the agent Message when no streaming activeMessageId exists" in {
      run().map { signals =>
        val agentMessage = signals.collect {
          case m: Message if m.participantId == TestAgent => m
        }.head
        val usageDeltas = signals.collect {
          case md: MessageDelta if md.usage.isDefined => md
        }
        usageDeltas should have size 1
        usageDeltas.head.target shouldBe agentMessage._id
        usageDeltas.head.usage.get.totalTokens shouldBe 4668
        usageDeltas.head.usage.get.promptTokens shouldBe 4622
        usageDeltas.head.usage.get.completionTokens shouldBe 46
      }
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
