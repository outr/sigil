package spec

import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Stream, Task}
import sigil.conversation.{Conversation, TurnInput}
import sigil.db.Model
import sigil.event.{Message, MessageRole, ToolInvoke}
import sigil.orchestrator.Orchestrator
import sigil.provider.{
  CallId, ConversationMode, ConversationRequest, GenerationSettings,
  Instructions, Provider, ProviderCall, ProviderEvent, ProviderType, StopReason
}
import sigil.signal.Signal
import sigil.tool.core.{ChangeModeTool, CoreTools}
import sigil.tool.model.{ChangeModeInput, ResponseContent}
import spice.http.HttpRequest

/**
 * Regression for the framework-state-as-Tool-role-text leakage path —
 * the agent's `ContextFrame` projection ends up carrying generic
 * framework strings ("tool failed: no result emitted", "This is a
 * duplicate `<tool>` call …") that poison the next turn's reasoning.
 *
 * Per the architectural fix: internal framework state must not surface
 * as Tool-role Message text. Retry / dedup / failure conditions are
 * expressed through typed channels — at most ONE result per ToolCall
 * in the agent's frame view, with content that names the tool / args
 * / error class concretely rather than emitting boilerplate prose
 * directives at the agent.
 *
 * Also covers the bonus issue: when a provider stream emits two
 * `ToolCallComplete` events with the same wire `callId`, the
 * orchestrator's orphan-synth path was firing on the second one and
 * producing a phantom ToolInvoke. Exactly one ToolInvoke must land
 * per `callId` per provider stream.
 */
class FrameworkStateLeakageSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  private val modelId: Id[Model] = Model.id("test", "framework-state-spec-model")

  private def buildRequest(convId: Id[Conversation]): ConversationRequest =
    ConversationRequest(
      conversationId = convId,
      modelId = modelId,
      instructions = Instructions(),
      turnInput = TurnInput(conversationId = convId),
      currentMode = ConversationMode,
      currentTopic = TestTopicEntry,
      previousTopics = Nil,
      generationSettings = GenerationSettings(maxOutputTokens = Some(50), temperature = Some(0.0)),
      chain = List(TestUser, TestAgent),
      tools = CoreTools.all.toVector :+ ChangeModeTool
    )

  /**
   * Provider that emits a fixed [[ProviderEvent]] stream.
   */
  final private class FixedProvider(events: List[ProviderEvent]) extends Provider {
    override def `type`: ProviderType = ProviderType.LlamaCpp
    override def models: List[Model] = Nil
    override protected def sigil: _root_.sigil.Sigil = TestSigil
    override def httpRequestFor(input: ProviderCall): Task[HttpRequest] =
      Task.error(new UnsupportedOperationException("no wire"))
    override def call(input: ProviderCall): Stream[ProviderEvent] = Stream.emits(events)
  }

  "Orphan-synth path" should {

    "emit exactly ONE ToolInvoke event when a real ToolCallStart preceded ToolCallComplete (no synth duplicate)" in {
      val convId = Conversation.id(s"single-invoke-${rapid.Unique()}")
      val conv = Conversation(topics = TestTopicStack, _id = convId)
      val request = buildRequest(convId)
      val provider = new FixedProvider(List(
        ProviderEvent.ToolCallStart(CallId("change-mode-1"), "change_mode"),
        ProviderEvent.ToolCallComplete(CallId("change-mode-1"), ChangeModeInput(mode = "conversation")),
        ProviderEvent.Done(StopReason.ToolCall)
      ))
      for {
        _ <- TestSigil.withDB(_.conversations.transaction(_.upsert(conv)))
        signals <- Orchestrator.process(TestSigil, provider, request, conv).toList
      } yield {
        val invokes = signals.collect { case t: ToolInvoke if t.callId.contains("change-mode-1") => t }
        invokes should have size 1
      }
    }

    "drop a duplicate ToolCallComplete for the same callId — no phantom synth ToolInvoke" in {
      // Reproduces the wire-log scenario where the upstream provider
      // emitted two function-call-complete chunks with the same callId.
      // Pre-fix: the orphan-synth fired on the second complete (since
      // activeCalls was already drained by the first complete) and
      // produced a phantom Active ToolInvoke with no paired result.
      val convId = Conversation.id(s"dupe-complete-${rapid.Unique()}")
      val conv = Conversation(topics = TestTopicStack, _id = convId)
      val request = buildRequest(convId)
      val provider = new FixedProvider(List(
        ProviderEvent.ToolCallStart(CallId("change-mode-dup"), "change_mode"),
        ProviderEvent.ToolCallComplete(CallId("change-mode-dup"), ChangeModeInput(mode = "conversation")),
        ProviderEvent.ToolCallComplete(CallId("change-mode-dup"), ChangeModeInput(mode = "conversation")),
        ProviderEvent.Done(StopReason.ToolCall)
      ))
      for {
        _ <- TestSigil.withDB(_.conversations.transaction(_.upsert(conv)))
        signals <- Orchestrator.process(TestSigil, provider, request, conv).toList
      } yield {
        val invokes = signals.collect { case t: ToolInvoke if t.callId.contains("change-mode-dup") => t }
        withClue(s"expected exactly 1 ToolInvoke for callId=change-mode-dup, got ${invokes.size}: $invokes: ") {
          invokes should have size 1
        }
      }
    }
  }

  "Framework state in agent frames" should {

    "not emit a Tool-role Message containing the generic 'This is a duplicate `<tool>` call' fallback when two identical tool calls happen in one completion" in {
      // The model emits change_mode twice with identical args in the
      // same completion. Pre-fix the framework dedup path emitted a
      // Tool-role Message with content "This is a duplicate `change_mode`
      // call with identical arguments to an earlier call …" — a
      // generic prose directive that pollutes the agent's next-turn
      // context window with framework state.
      val convId = Conversation.id(s"dupe-args-${rapid.Unique()}")
      val conv = Conversation(topics = TestTopicStack, _id = convId)
      val request = buildRequest(convId)
      val provider = new FixedProvider(List(
        ProviderEvent.ToolCallStart(CallId("change-mode-a"), "change_mode"),
        ProviderEvent.ToolCallComplete(CallId("change-mode-a"), ChangeModeInput(mode = "conversation")),
        ProviderEvent.ToolCallStart(CallId("change-mode-b"), "change_mode"),
        ProviderEvent.ToolCallComplete(CallId("change-mode-b"), ChangeModeInput(mode = "conversation")),
        ProviderEvent.Done(StopReason.ToolCall)
      ))
      for {
        _ <- TestSigil.withDB(_.conversations.transaction(_.upsert(conv)))
        signals <- Orchestrator.process(TestSigil, provider, request, conv).toList
      } yield {
        val offendingProse = signals.collect {
          case m: Message if m.role == MessageRole.Tool =>
            m.content.collect {
              case ResponseContent.Text(t) => t
              case ResponseContent.Markdown(t) => t
            }.mkString(" ")
        }.filter(_.contains("This is a duplicate"))
        withClue(s"agent frame trail must not carry the generic 'This is a duplicate' prose directive: $offendingProse: ") {
          offendingProse shouldBe empty
        }
      }
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
