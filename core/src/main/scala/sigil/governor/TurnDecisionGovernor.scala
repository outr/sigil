package sigil.governor

import lightdb.id.Id
import rapid.Task
import sigil.Sigil
import sigil.conversation.Conversation
import sigil.event.{Event, Message, MessageDisposition, ToolInvoke}
import sigil.orchestrator.{Directive, Orchestrator, SyntheticDiagnostic}
import sigil.participant.AgentParticipantId
import sigil.provider.{Provider, StopReason}
import sigil.signal.{EventState, MessageDelta, Signal}
import sigil.tool.model.ResponseContent

/**
 * Naked-text terminal guard — a plain-prose `end_turn` with no tool call
 * carries no explicit continue-vs-yield decision, because that decision
 * lives on `respond`'s required `endsTurn` field and prose bypasses it.
 * Reading the prose as an implicit yield is the announce-then-stall
 * failure: the model narrates its next step, stops, and the turn ends
 * with the announced work undone.
 *
 * So a naked-text terminal is challenged rather than committed — a
 * `_turn_decision_required` Tool-role diagnostic telling the model to
 * either execute the work it announced or end the turn explicitly via
 * the respond family. The challenge re-fires with an escalated directive
 * when the model answers it with more prose (a one-shot guard would
 * guarantee the second narration is accepted as terminal — the exact
 * stall it exists to break), bounded per user turn by
 * [[sigil.Sigil.nakedTextChallengeLimit]]. Past the budget — or on a
 * forced-synthesis wrap-up, or when the turn's context was elided under
 * pressure so the model could only narrate — the prose commits as the
 * terminal reply.
 *
 * Two wire shapes carry the prose, and the verdict differs:
 *
 *   - `ContentBlockDelta` (Anthropic / OpenAI Responses / Google) — a
 *     Message was born and has already streamed to clients, so it
 *     settles Complete either way: WITHOUT `terminalReply` on the
 *     challenge path (a visible progress message; the turn continues),
 *     WITH it on the commit path.
 *   - `TextDelta` (OpenAI-compatible chat-completions) — only meaningful
 *     for a model in the forced-tool_choice rejecter memo (one that
 *     actually ran on `auto`; any other model's prose on this wire is
 *     drift and belongs to [[PlainTextReplyGovernor]]). For such a model
 *     the orchestrator streams the prose into a born Message, so the
 *     verdict is the same as the block wire's above. Prose that never
 *     streamed — the memo was recorded after this stream began — settles
 *     through the mint path: nothing is minted on the challenge path (the
 *     challenge carries the dropped text so the model can re-wrap it),
 *     and the commit path mints and settles terminally.
 */
final class TurnDecisionGovernor extends OutcomeGovernor {
  override def name: String = "turn-decision"

  override def evaluate(outcome: TurnOutcome, host: Sigil): Task[OutcomeVerdict] = {
    val convId = outcome.conversationId
    val emit: List[Signal] => OutcomeVerdict = {
      case Nil => OutcomeVerdict.Proceed
      case nonEmpty => OutcomeVerdict.Emit(nonEmpty)
    }
    if (outcome.stopReason != StopReason.Complete || outcome.sawToolCall) Task.pure(OutcomeVerdict.Proceed)
    else if (outcome.activeMessageCreated) {
      // Snapshot the streamed text into the settle — the per-delta
      // `ContentBlockDelta`s were `complete = false` (snapshot-only
      // persistence ignores them), so without this the committed
      // Message would settle empty.
      val text = outcome.streamedText
      def settle(terminal: Boolean): List[Signal] =
        outcome.activeMessageId.toList.map(id =>
          MessageDelta(
            target = id,
            conversationId = convId,
            contentReplacement = Some(Vector(ResponseContent.Text(text))),
            state = Some(EventState.Complete),
            terminalReply = terminal))
      if (outcome.forceResponseSynthesis || outcome.contextPressured) Task.pure(emit(settle(terminal = true)))
      else challengeCount(host, convId).map { count =>
        if (count >= host.nakedTextChallengeLimit) emit(settle(terminal = true))
        else
          emit(settle(terminal = false) ++ challenge(outcome, droppedText = None, priorChallenges = count))
      }
    } else if (outcome.bufferedText.nonEmpty && Provider.rejectsForcedToolChoice(outcome.modelId)) {
      val text = outcome.bufferedText
      def mintTerminal: List[Signal] = {
        val id = outcome.activeMessageId.getOrElse(Event.id())
        val msg = Message(
          participantId = outcome.caller,
          conversationId = convId,
          topicId = outcome.topicId,
          content = Vector.empty,
          modelId = Some(outcome.modelId),
          modelDisplayName = outcome.modelDisplayName,
          _id = id,
          state = EventState.Active
        )
        List(
          msg,
          MessageDelta(
            target = id,
            conversationId = convId,
            contentReplacement = Some(Vector(ResponseContent.Text(text))),
            state = Some(EventState.Complete),
            terminalReply = true)
        )
      }
      if (outcome.forceResponseSynthesis || outcome.contextPressured) Task.pure(emit(mintTerminal))
      else challengeCount(host, convId).map { count =>
        if (count >= host.nakedTextChallengeLimit) emit(mintTerminal)
        else emit(challenge(outcome, droppedText = Some(text), priorChallenges = count))
      }
    } else Task.pure(OutcomeVerdict.Proceed)
  }

  /**
   * The (synthetic-invoke, Failure-message) pair. The invoke's
   * `_turn_decision_required` name doubles as the marker
   * [[challengeCount]] walks for, and the Nth bare narration is stronger
   * evidence of an announce-then-stall than the first, so the directive
   * escalates with the count rather than repeating itself.
   */
  private def challenge(outcome: TurnOutcome,
                        droppedText: Option[String],
                        priorChallenges: Int): List[Signal] =
    SyntheticDiagnostic(
      Directive.TurnDecisionRequired(droppedText, priorChallenges),
      outcome.caller,
      outcome.conversationId,
      outcome.topicId,
      disposition = MessageDisposition.Failure(recoverable = true)
    )

  /**
   * Challenges already issued since the last user-authored Message —
   * the per-user-turn budget.
   */
  private def challengeCount(host: Sigil, convId: Id[Conversation]): Task[Int] =
    host.withDB(_.conversationEventsConsistent(convId)).map { allEvents =>
      val convEvents = allEvents.sortBy(_.timestamp.value)
      val lastUserIdx = convEvents.lastIndexWhere {
        case m: Message => !m.participantId.isInstanceOf[AgentParticipantId]
        case _ => false
      }
      val tail = if (lastUserIdx < 0) convEvents else convEvents.drop(lastUserIdx + 1)
      tail.count {
        case ti: ToolInvoke => ti.toolName.value == Orchestrator.TurnDecisionToolName
        case _ => false
      }
    }
}
