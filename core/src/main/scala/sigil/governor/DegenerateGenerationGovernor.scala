package sigil.governor

import rapid.Task
import sigil.Sigil
import sigil.event.MessageDisposition
import sigil.orchestrator.{Directive, SyntheticDiagnostic}
import sigil.provider.{DegenerateContentDetector, StopReason}

/** Token-level repetition guard — the turn hit `max_tokens` AND its
  * generated text is dominated by a single repeated sentence. The
  * diagnostic names the loop concretely so the next iteration
  * self-corrects rather than reading the whole degenerate tail back into
  * the prompt.
  *
  * Only a `max_tokens` stop is evidence: a repetition loop is what
  * exhausts the budget, and a turn that ended for any other reason
  * finished saying what it meant to say.
  */
final class DegenerateGenerationGovernor(detector: DegenerateContentDetector = DegenerateContentDetector.Default)
  extends OutcomeGovernor {

  override def name: String = "degenerate-generation"

  override def evaluate(outcome: TurnOutcome, host: Sigil): Task[OutcomeVerdict] = Task {
    outcome.stopReason match {
      case StopReason.MaxTokens =>
        val text = outcome.generatedText
        detector.detect(text) match {
          case Some(hit) =>
            scribe.warn(s"orchestrator: degenerate generation detected (${hit.occurrences}/${hit.totalSentences} sentences " +
              s"= ${math.round(hit.share * 100)}% repetition) in conversation ${outcome.conversationId} — emitting Failure diagnostic")
            OutcomeVerdict.Emit(SyntheticDiagnostic(
              Directive.DegenerateGeneration(hit.repeatedSentence, hit.occurrences,
                hit.totalSentences, hit.share, text.length),
              outcome.caller, outcome.conversationId, outcome.topicId,
              disposition = MessageDisposition.Failure(recoverable = true)))
          case None => OutcomeVerdict.Proceed
        }
      case _ => OutcomeVerdict.Proceed
    }
  }
}
