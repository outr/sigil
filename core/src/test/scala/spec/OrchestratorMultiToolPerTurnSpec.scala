package spec

import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Stream, Task}
import sigil.{GlobalSpace, SpaceId, TurnContext}
import sigil.conversation.{ConversationView, Conversation, TurnInput}
import sigil.db.Model
import sigil.event.{Event, Message, ToolInvoke}
import sigil.orchestrator.Orchestrator
import sigil.provider.{
  CallId, ConversationMode, ConversationRequest, GenerationSettings,
  Instructions, Provider, ProviderCall, ProviderEvent, ProviderType, StopReason
}
import sigil.signal.{EventState, Signal, ToolDelta}
import sigil.tool.{JsonInput, Tool, ToolInput, ToolName}
import sigil.tool.core.{NoResponseTool, RespondTool}
import sigil.tool.model.NoResponseInput
import spice.http.HttpRequest
import fabric.rw.*

/**
 * Regression for bug #49 — when a turn contains two atomic tool
 * calls back-to-back (no intervening `respond` text), an exception
 * thrown by the second tool's `execute` used to take down BOTH the
 * second `ToolDelta(input=Some(...))` and any `ToolResults` events
 * — agent saw the second tool chip stuck "input pending" and no
 * follow-up. The orchestrator now wraps stream construction in
 * `Task(...).handleError`, surfacing the failure as a Tool-role
 * Message instead.
 */
class OrchestratorMultiToolPerTurnSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  private val modelId: Id[Model] = Model.id("test", "model")

  /** Atomic tool that throws synchronously inside `execute`. */
  private object ThrowingTool extends Tool {
    override val name: ToolName = ToolName("throw_atomic")
    override def description: String = "Always throws on execute."
    override def inputRW: RW[? <: ToolInput] = summon[RW[NoResponseInput]]
    override def space: SpaceId = GlobalSpace
    override def execute(input: ToolInput, context: TurnContext): Stream[Event] =
      throw new RuntimeException("synthetic atomic-tool failure")
    override def _id: Id[Tool] = Id[Tool](name.value)
  }

  /** Provider that emits two tool calls back-to-back (no respond
    * text between them) then Done. The second tool's name is
    * `ThrowingTool`, which fails synchronously. */
  private class TwoCallProvider extends Provider {
    override def `type`: ProviderType = ProviderType.LlamaCpp
    override def models: List[_root_.sigil.db.Model] = Nil
    override protected def sigil: _root_.sigil.Sigil = TestSigil
    override protected def httpRequestFor(input: ProviderCall): Task[HttpRequest] =
      Task.error(new UnsupportedOperationException("no wire"))
    override protected def call(input: ProviderCall): Stream[ProviderEvent] = {
      val callA = CallId("call-a")
      val callB = CallId("call-b")
      Stream.emits(List(
        ProviderEvent.ToolCallStart(callA, NoResponseTool.schema.name.value),
        ProviderEvent.ToolCallComplete(callA, NoResponseInput()),
        ProviderEvent.ToolCallStart(callB, ThrowingTool.name.value),
        ProviderEvent.ToolCallComplete(callB, NoResponseInput()),
        ProviderEvent.Done(StopReason.Complete)
      ))
    }
  }

  private def runWith(provider: Provider, suffix: String): Task[List[Signal]] = {
    val convId = Conversation.id(s"multi-tool-$suffix")
    val conv = Conversation(topics = TestTopicStack, _id = convId)
    val view = ConversationView(conversationId = convId, _id = ConversationView.idFor(convId))
    val request = ConversationRequest(
      conversationId     = convId,
      modelId            = modelId,
      instructions       = Instructions(),
      turnInput          = TurnInput(view),
      currentMode        = ConversationMode,
      currentTopic       = TestTopicEntry,
      previousTopics     = Nil,
      generationSettings = GenerationSettings(maxOutputTokens = Some(50), temperature = Some(0.0)),
      chain              = List(TestUser, TestAgent),
      // Both tools must be in the request roster — the orchestrator
      // looks them up by name on `ToolCallComplete`.
      tools              = Vector(NoResponseTool, ThrowingTool)
    )
    for {
      _       <- TestSigil.withDB(_.conversations.transaction(_.upsert(conv)))
      signals <- Orchestrator.process(TestSigil, provider, request, conv).toList
    } yield signals
  }

  "Orchestrator (bug #49)" should {
    "surface ToolDelta for both tool calls in a turn even when the second tool throws" in {
      runWith(new TwoCallProvider, suffix = "throw").map { signals =>
        // Two ToolInvoke events, one per tool call.
        val invokes = signals.collect { case t: ToolInvoke => t }
        invokes should have size 2
        invokes.map(_.toolName.value) shouldBe List(
          NoResponseTool.schema.name.value,
          ThrowingTool.name.value
        )

        // Two settled ToolDeltas — one per call. Critically the SECOND
        // one (which targets the throwing tool) must land despite the
        // tool's execute throwing.
        val deltas = signals.collect { case d: ToolDelta if d.state.contains(EventState.Complete) => d }
        deltas should have size 2
        val targets = deltas.map(_.target).toSet
        targets shouldBe invokes.map(_._id).toSet

        // The thrown failure surfaces as a Tool-role Message rather
        // than tearing down the whole stream.
        val toolMessages = signals.collect { case m: Message => m }
        toolMessages.exists(_.content.exists {
          case sigil.tool.model.ResponseContent.Text(t) => t.contains("execution failed")
          case _                                        => false
        }) shouldBe true
      }
    }
  }
}
