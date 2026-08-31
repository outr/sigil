package spec

import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Stream, Task}
import sigil.conversation.{Conversation, TurnInput}
import sigil.db.Model
import sigil.event.{Message, MessageRole, ToolInvoke, ToolOutcome}
import sigil.orchestrator.Orchestrator
import sigil.provider.{
  CallId, ConversationMode, ConversationRequest, GenerationSettings,
  Instructions, Provider, ProviderCall, ProviderEvent, ProviderType, StopReason
}
import sigil.signal.{EventState, Signal, ToolDelta}
import sigil.tool.core.RespondTool
import sigil.tool.model.ResponseContent
import spice.http.HttpRequest

/**
 * Regression coverage for bug #123 — the wire-log scenario where a
 * streaming completion settles with `finish_reason=length` mid-
 * tool-args. The provider truncated the JSON before the args
 * closed; the tool never runs.
 *
 * Pre-fix the orchestrator's `Done(MaxTokens)` handler emitted only
 * an orphan `ToolDelta(input=None, state=Complete)`. The frame
 * renderer notices the missing pair and surfaces a misleading
 * "tool's executeTyped — please report it" framework error to the
 * next iteration's prompt, sending the agent into a re-emit loop
 * with no signal that args-size is the real problem.
 *
 * Post-fix every orphaned active call gets a paired Tool-role
 * `Failure` Message diagnosing args-truncation, with `origin` set
 * to the orphan invoke's id so the frame renderer sees a paired
 * function_call ↔ function_call_output.
 */
class OrchestratorMaxTokensTruncationSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  private val modelId: Id[Model] = Model.id("test", "max-tokens-truncation")

  /**
   * Provider that emits a `ToolCallStart` and then settles with
   * `Done(StopReason.MaxTokens)` while the call is still open —
   * exactly the wire-log scenario.
   */
  private class TruncatedArgsProvider extends Provider {
    override def `type`: ProviderType = ProviderType.LlamaCpp
    override def models: List[_root_.sigil.db.Model] = Nil
    override protected def sigil: _root_.sigil.Sigil = TestSigil
    override def httpRequestFor(input: ProviderCall): Task[HttpRequest] =
      Task.error(new UnsupportedOperationException("no wire"))
    override def call(input: ProviderCall): Stream[ProviderEvent] = {
      val callId = CallId("truncated-call")
      Stream.emits(List(
        ProviderEvent.ToolCallStart(callId, "lsp_did_change"),
        // No ToolCallComplete — the args got truncated.
        ProviderEvent.Done(StopReason.MaxTokens)
      ))
    }
  }

  /**
   * Sanity-control provider: same Done(MaxTokens) but no in-flight
   * tool call. The truncation diagnostic must NOT fire here — it's
   * scoped to orphaned calls only.
   */
  private class IdleMaxTokensProvider extends Provider {
    override def `type`: ProviderType = ProviderType.LlamaCpp
    override def models: List[_root_.sigil.db.Model] = Nil
    override protected def sigil: _root_.sigil.Sigil = TestSigil
    override def httpRequestFor(input: ProviderCall): Task[HttpRequest] =
      Task.error(new UnsupportedOperationException("no wire"))
    override def call(input: ProviderCall): Stream[ProviderEvent] =
      Stream.emit(ProviderEvent.Done(StopReason.MaxTokens))
  }

  private def runWith(provider: Provider, suffix: String): Task[List[Signal]] = {
    val convId = Conversation.id(s"max-tokens-trunc-$suffix")
    val conv = Conversation(topics = TestTopicStack, _id = convId)
    val request = ConversationRequest(
      conversationId = convId,
      model = TestSigil.testModel(modelId),
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
      signals <- Orchestrator.process(TestSigil, provider, request, conv).toList
    } yield signals
  }

  "Orchestrator on Done(MaxTokens) with an in-flight tool call" should {

    "emit a settling ToolDelta with Failure outcome diagnosing args-truncation" in
      runWith(new TruncatedArgsProvider, suffix = "truncation").map { signals =>
        val invoke = signals.collectFirst { case t: ToolInvoke => t }
          .getOrElse(fail("Expected a ToolInvoke for the orphaned call"))
        // Sigil #265 — the orphan invoke is self-settling: one
        // ToolDelta folds state = Complete + outcome = Failure(reason,
        // recoverable = true) onto the invoke. The agent reads the
        // failure on its next iteration via the invoke's own settled
        // `outcome`/`summary` — no separate paired event.
        val terminalDelta = signals.collect { case d: ToolDelta => d }
          .find(_.target == invoke._id)
          .getOrElse(fail(s"Expected a settling ToolDelta for invoke ${invoke._id.value}; saw none"))
        terminalDelta.state shouldBe Some(EventState.Complete)
        terminalDelta.input shouldBe None
        terminalDelta.outcome match {
          case Some(ToolOutcome.Failure(reason, recoverable)) =>
            reason should include("lsp_did_change")
            reason should include("max_tokens")
            reason should include("arguments never fully arrived")
            recoverable shouldBe true
          case other =>
            fail(s"Expected ToolOutcome.Failure; saw $other")
        }
      }

    "NOT emit the misleading 'tool emitted no MessageRole.Tool event' framework error" in
      // The settled invoke closes the function_call ↔ function_call_output
      // pair via its own outcome; the frame renderer no longer needs
      // to synthesize its "please report it" placeholder.
      runWith(new TruncatedArgsProvider, suffix = "no-report-it").map { signals =>
        val anyTextMentions = signals.collect {
          case m: Message =>
            m.content.collectFirst { case ResponseContent.Text(t) => t }
              .orElse(m.failureReason).getOrElse("")
          case d: ToolDelta =>
            d.outcome match {
              case Some(ToolOutcome.Failure(reason, _)) => reason
              case _ => d.summary.getOrElse("")
            }
          case _ => ""
        }
        all(anyTextMentions) should not include "please report it"
        all(anyTextMentions) should not include "tool's executeTyped"
      }

    "NOT emit a truncation diagnostic when Done(MaxTokens) fires with no in-flight call" in
      runWith(new IdleMaxTokensProvider, suffix = "idle-max").map { signals =>
        // No orphan-settling Failure deltas should land — there's no
        // orphan invoke to settle. The degenerate-content path (which
        // fires on text-buffer repetition) is independent and not
        // exercised here.
        val failureDeltas = signals.collect {
          case d: ToolDelta if d.outcome.exists(_.isInstanceOf[ToolOutcome.Failure]) => d
        }
        failureDeltas shouldBe empty
      }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
