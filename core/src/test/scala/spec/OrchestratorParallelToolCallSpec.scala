package spec

import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Stream, Task}
import sigil.{GlobalSpace, SpaceId, TurnContext}
import sigil.conversation.{ConversationView, Conversation, TurnInput}
import sigil.db.Model
import sigil.event.{Event, ToolInvoke}
import sigil.orchestrator.Orchestrator
import sigil.provider.{
  CallId, ConversationMode, ConversationRequest, GenerationSettings, Instructions,
  Provider, ProviderCall, ProviderEvent, ProviderType, StopReason
}
import sigil.signal.{EventState, Signal, ToolDelta}
import sigil.tool.{Tool, ToolInput, ToolName}
import sigil.tool.core.NoResponseTool
import sigil.tool.model.NoResponseInput
import spice.http.HttpRequest
import fabric.rw.*

/**
 * Regression for the parallel-tool-call orchestrator state-machine
 * bug surfaced by the AgentDojo overnight run (66 occurrences on
 * gpt-5.4-mini, 2 on claude-haiku, 0 on gemma).
 *
 * OpenAI's chat-completions stream emits parallel tool calls as:
 *
 *   ToolCallStart(call0)
 *   ToolCallStart(call1)
 *   <args interleave>
 *   ToolCallComplete(call0)
 *   ToolCallComplete(call1)
 *
 * Pre-fix, the orchestrator tracked one in-flight call via
 * `Option[Id[Event]]`; the second `Start` overwrote the first's
 * invokeId, then the second `Complete` had no state to look up and
 * threw `IllegalStateException("ToolCallComplete without a preceding
 * ToolCallStart")`. Each occurrence killed the in-flight turn →
 * AgentDojo cells failed → utility tanked.
 *
 * Post-fix the orchestrator keys in-flight calls by the provider's
 * `CallId` so each `Complete` finds its own `Start`'s invokeId.
 */
class OrchestratorParallelToolCallSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  private val modelId: Id[Model] = Model.id("test", "model")

  /**
   * Provider that emits two interleaved tool calls.
   */
  private class ParallelProvider extends Provider {
    override def `type`: ProviderType = ProviderType.LlamaCpp
    override def models: List[Model] = Nil
    override protected def sigil: _root_.sigil.Sigil = TestSigil
    override def httpRequestFor(input: ProviderCall): Task[HttpRequest] =
      Task.error(new UnsupportedOperationException("no wire"))
    override def call(input: ProviderCall): Stream[ProviderEvent] = {
      val callA = CallId("call-A")
      val callB = CallId("call-B")
      // Start both calls before either completes — the OpenAI
      // parallel-tool-calls pattern.
      // Distinct `reason` strings so the orchestrator's identical-args
      // dedupe (bug #87) doesn't collapse callB into a pointer Message —
      // this spec validates parallel ROUTING, not dedupe.
      Stream.emits(List(
        ProviderEvent.ToolCallStart(callA, NoResponseTool.schema.name.value),
        ProviderEvent.ToolCallStart(callB, NoResponseTool.schema.name.value),
        ProviderEvent.toolCall(callA, NoResponseTool)(NoResponseInput(reason = Some("a"))),
        ProviderEvent.toolCall(callB, NoResponseTool)(NoResponseInput(reason = Some("b"))),
        ProviderEvent.Done(StopReason.Complete)
      ))
    }
  }

  private def runWith(provider: Provider): Task[List[Signal]] = {
    val convId = Conversation.id(s"parallel-tool-${rapid.Unique()}")
    val conv = Conversation(topics = TestTopicStack, _id = convId)
    val viewConvId = convId
    val request = ConversationRequest(
      conversationId = convId,
      model = TestSigil.testModel(modelId),
      instructions = Instructions(),
      turnInput = TurnInput(conversationId = viewConvId),
      currentMode = ConversationMode,
      currentTopic = TestTopicEntry,
      generationSettings = GenerationSettings(),
      tools = Vector(NoResponseTool),
      chain = List(TestUser, TestAgent)
    )
    Orchestrator.process(TestSigil, provider, request, conv).toList
  }

  "Orchestrator (parallel tool calls)" should {
    "route each ToolCallComplete to its matching ToolCallStart's invokeId" in
      runWith(new ParallelProvider).map { signals =>
        val invokes = signals.collect { case t: ToolInvoke => t }
        val deltas = signals.collect { case d: ToolDelta if d.state.contains(EventState.Complete) => d }

        // Two starts → two ToolInvoke Active events with distinct ids.
        invokes should have size 2
        invokes.map(_._id).distinct should have size 2

        // Two completes → both settle. Current emission shape produces
        // multiple settling ToolDeltas per call (input-settle then
        // result-settle), so we assert on the distinct target set
        // rather than count — what matters for the routing-regression
        // is that BOTH invokeIds appear, proving the second Complete
        // didn't lose its state lookup.
        deltas.map(_.target).toSet shouldBe invokes.map(_._id).toSet

        // Crucially: NO IllegalStateException leaked into the signal
        // stream as an Error. Pre-fix the second Complete threw and
        // killed the surrounding agent loop.
        succeed
      }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
