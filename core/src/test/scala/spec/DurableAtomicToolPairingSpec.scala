package spec

import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Stream, Task}
import sigil.conversation.{Conversation, TurnInput}
import sigil.db.Model
import sigil.event.{Event, Message, MessageRole, ToolInvoke}
import sigil.orchestrator.Orchestrator
import sigil.provider.{
  CallId, ConversationMode, ConversationRequest, GenerationSettings, Instructions,
  Provider, ProviderCall, ProviderEvent, ProviderType, StopReason
}
import sigil.signal.{EventState, Signal}
import sigil.tool.ToolName
import sigil.tool.core.{CoreTools, RespondTool}
import sigil.tool.model.RespondInput
import spice.http.HttpRequest

/**
 * Regression for sigil bug #174 (the durable-fix version) — every
 * atomic-content tool's emission must be followed by a Tool-role
 * completion event paired to its ToolInvoke via `origin`. This makes
 * the synthetic empty `function_call_output` (sigil bug #19's wire-
 * side fix) a DURABLE artifact in the event log rather than an
 * inline render-time hack.
 *
 * Consequence: when subsequent iterations rebuild context from
 * `db.events`, the frame trail naturally ends with a Tool-role frame
 * after every respond, not an assistant Text frame. The wire request's
 * trailing role is `tool`, never `assistant`-from-respond. Chat
 * templates that distinguish "agent said this" from "agent said this
 * AND is continuing" (Qwen3.6 with `enable_thinking: true`) no longer
 * 400 on multi-iteration agent turns.
 *
 * Closes the architectural family:
 *   - bug #19: OpenAI Responses' strict pairing
 *   - bug #174: Qwen3.6's prefill rejection
 *   - any future provider's chat template distinguishing
 *     trailing-assistant semantics
 */
class DurableAtomicToolPairingSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  private val modelId: Id[Model] = Model.id("test", "durable-pair-model")

  /**
   * Fake provider that emits a single `respond` tool call with valid
   * `RespondInput` args, then settles. Drives `Orchestrator.process`
   * through its atomic-content dispatch path.
   */
  private class RespondingProvider extends Provider {
    override def `type`: ProviderType = ProviderType.LlamaCpp
    override def models: List[Model] = Nil
    override protected def sigil: _root_.sigil.Sigil = TestSigil
    override def httpRequestFor(input: ProviderCall): Task[HttpRequest] =
      Task.error(new UnsupportedOperationException("no wire"))
    override def call(input: ProviderCall): Stream[ProviderEvent] = {
      val cid = CallId("durable-respond-call")
      val respondInput = RespondInput(
        topicLabel = "Greeting",
        topicSummary = "test",
        content = "Hello world",
        endsTurn = true
      )
      Stream.emits(List(
        ProviderEvent.ToolCallStart(cid, "respond"),
        ProviderEvent.ToolCallComplete(cid, respondInput),
        ProviderEvent.Done(StopReason.Complete)
      ))
    }
  }

  private def runOrchestrator(): Task[List[Signal]] = {
    val convId = Conversation.id("durable-atomic-conv")
    val conv = Conversation(topics = TestTopicStack, _id = convId)
    val request = ConversationRequest(
      conversationId = convId,
      modelId = modelId,
      instructions = Instructions(),
      turnInput = TurnInput(conversationId = convId),
      currentMode = ConversationMode,
      currentTopic = TestTopicEntry,
      previousTopics = Nil,
      generationSettings = GenerationSettings(maxOutputTokens = Some(50), temperature = Some(0.0)),
      chain = List(TestUser, TestAgent),
      tools = Vector(RespondTool)
    )
    for {
      _ <- TestSigil.withDB(_.conversations.transaction(_.upsert(conv)))
      signals <- Orchestrator.process(TestSigil, new RespondingProvider, request, conv).toList
    } yield signals
  }

  "Bug #174 (durable) — atomic-content tool's Tool-role pairing" should {

    "include a Tool-role Message paired to the respond's ToolInvoke" in
      runOrchestrator().map { signals =>
        val invoke = signals.collectFirst {
          case ti: ToolInvoke if ti.toolName == ToolName("respond") => ti
        }.getOrElse(fail("expected a respond ToolInvoke"))

        val toolRoleEvents = signals.collect {
          case m: Message if m.role == MessageRole.Tool && m.origin.contains(invoke._id) => m
        }
        withClue(
          s"expected a Tool-role Message paired to respond invoke ${invoke._id.value}; signals: ${signals.map(_.getClass.getSimpleName)}: ") {
          toolRoleEvents should have size 1
        }
      }

    "the synthetic Tool-role Message has empty content" in
      // Empty content → wire renders an empty function_call_output,
      // which is the canonical "atomic-content tool's user-visible
      // output is the Message itself; no further data to feed back."
      runOrchestrator().map { signals =>
        val invoke = signals.collectFirst { case ti: ToolInvoke if ti.toolName == ToolName("respond") => ti }.get
        val pair = signals.collectFirst {
          case m: Message if m.role == MessageRole.Tool && m.origin.contains(invoke._id) => m
        }.get
        pair.content shouldBe empty
      }

    "emit the synthetic alongside the user-visible Message (not replacing it)" in
      runOrchestrator().map { signals =>
        val userVisible = signals.collect {
          case m: Message if m.role == MessageRole.Standard => m
        }
        val toolRole = signals.collect {
          case m: Message if m.role == MessageRole.Tool => m
        }
        withClue(s"signals: ${signals.map(_.getClass.getSimpleName)}: ") {
          userVisible should have size 1
          toolRole should have size 1
          // The user-visible Message carries the actual respond content.
          userVisible.head.content should not be empty
        }
      }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
