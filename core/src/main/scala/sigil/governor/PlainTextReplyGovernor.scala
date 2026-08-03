package sigil.governor

import rapid.Task
import sigil.Sigil
import sigil.event.MessageDisposition
import sigil.orchestrator.{Directive, SyntheticDiagnostic}
import sigil.provider.{Provider, StopReason}

/** Plain-text drift guard — the model answered in prose without wrapping
  * it in a respond-family tool, on a turn that ran with `tool_choice`
  * forcing a tool call. The prose is dropped and the drop is surfaced as
  * a recoverable Tool-role failure so the next iteration reads a concrete
  * diagnostic instead of the turn silently ending.
  *
  * Prose means something different for a model in the forced-tool_choice
  * rejecter memo: its forced choice was downgraded to `auto`, so a
  * `Complete` prose turn is its committed answer, not drift, and it
  * belongs to [[TurnDecisionGovernor]] instead. Only the chat-completions
  * wire can drift this way — grammar-constrained providers cannot emit
  * prose under a forced tool choice — which is why the evidence read here
  * is the buffered `TextDelta` text.
  */
final class PlainTextReplyGovernor extends OutcomeGovernor {
  override def name: String = "plain-text-reply"

  override def evaluate(outcome: TurnOutcome, host: Sigil): Task[OutcomeVerdict] = Task {
    val autoAnswered =
      outcome.stopReason == StopReason.Complete && Provider.rejectsForcedToolChoice(outcome.modelId)
    if (outcome.bufferedText.nonEmpty && !outcome.sawToolCall && !autoAnswered)
      OutcomeVerdict.Emit(SyntheticDiagnostic(
        Directive.PlainTextReply(outcome.bufferedText),
        outcome.caller, outcome.conversationId, outcome.topicId,
        disposition = MessageDisposition.Failure(recoverable = true)))
    else OutcomeVerdict.Proceed
  }
}
