package spec

import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Stream, Task}
import sigil.conversation.{Conversation, TurnInput}
import sigil.db.Model
import sigil.event.ToolInvoke
import sigil.orchestrator.Orchestrator
import sigil.provider.{
  CallId, ConversationMode, ConversationRequest, GenerationSettings,
  Instructions, Provider, ProviderCall, ProviderEvent, ProviderType, StopReason
}
import sigil.signal.{EventState, Signal, ToolDelta}
import sigil.tool.core.NoResponseTool
import sigil.tool.model.NoResponseInput
import spice.http.HttpRequest

/**
 * Regression for Sigil bug #176 — OpenRouter (and other OpenAI-compat
 * gateways proxying Kimi-K2.5) occasionally stream tool calls in a
 * shape that doesn't trigger `ToolCallStart` upstream. The result was
 * `IllegalStateException: ToolCallComplete(...) without a preceding
 * ToolCallStart` thrown from the orchestrator, tearing down the
 * agent loop on the first request of a session.
 *
 * Fix: the orchestrator synthesizes the ActiveCall + ToolInvoke event
 * in-line when it sees a ToolCallComplete with no matching active
 * call. The tool name is recovered from the typed input's runtime
 * class (each tool's input type is unique).
 */
class OrphanToolCallCompleteSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  private val modelId: Id[Model] = Model.id("test", "kimi-like")

  /** Provider that emits `ToolCallComplete` WITHOUT a prior
    * `ToolCallStart` — the bug-#176 wire shape. */
  private class OrphanCompleteProvider extends Provider {
    override def `type`: ProviderType = ProviderType.LlamaCpp
    override def models: List[_root_.sigil.db.Model] = Nil
    override protected def sigil: _root_.sigil.Sigil = TestSigil
    override def httpRequestFor(input: ProviderCall): Task[HttpRequest] =
      Task.error(new UnsupportedOperationException("no wire"))
    override def call(input: ProviderCall): Stream[ProviderEvent] = Stream.emits(List(
      // No ToolCallStart — that's the bug shape.
      ProviderEvent.ToolCallComplete(CallId("functions.no_response:24"), NoResponseInput()),
      ProviderEvent.Done(StopReason.Complete)
    ))
  }

  private def runOnce(provider: Provider, suffix: String): Task[List[Signal]] = {
    val convId = Conversation.id(s"orphan-tcc-$suffix")
    val conv = Conversation(topics = TestTopicStack, _id = convId)
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
      tools              = Vector(NoResponseTool)
    )
    for {
      _       <- TestSigil.withDB(_.conversations.transaction(_.upsert(conv)))
      signals <- Orchestrator.process(TestSigil, provider, request, conv).toList
    } yield signals
  }

  "Bug #176 — orphan ToolCallComplete (no prior ToolCallStart)" should {

    "synthesize the ToolInvoke event in-line and complete the turn without throwing" in {
      runOnce(new OrphanCompleteProvider, suffix = "synth").map { signals =>
        // ToolInvoke is synthesized — exactly one, for the orphan tool.
        val invokes = signals.collect { case t: ToolInvoke => t }
        invokes should have size 1
        invokes.head.toolName.value shouldBe NoResponseTool.schema.name.value
        invokes.head.callId shouldBe Some("functions.no_response:24")

        // The synthesized invoke is settled by the corresponding ToolDelta.
        val settleDeltas = signals.collect {
          case d: ToolDelta if d.state.contains(EventState.Complete) => d
        }
        settleDeltas.map(_.target).toSet should contain (invokes.head._id)
      }
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
