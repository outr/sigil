package sigil.governor

import rapid.Task
import sigil.orchestrator.Directive
import sigil.{ForcedSynthesisReason, Sigil}

/** Refusal-loop guard — ends a turn the duplicate-call cap can only keep
  * refusing.
  *
  * The cap is the detector: it recognises an identical re-issue and answers
  * it with a Tool-role Failure. That Failure is itself a trigger, so a model
  * that re-issues regardless collects one refusal per iteration until the
  * ceiling throws — detection with no way to terminate what it detects.
  * Past [[sigil.Sigil.duplicateRefusalLimit]] refusals of one call group,
  * this governor stops the turn and hands it to forced synthesis, so the
  * user gets whatever the agent did gather instead of a runaway.
  *
  * Last in the default order — a backstop, not a preemption. Every richer
  * guard ahead of it keeps the boundaries it would have claimed anyway, and
  * by the time this can fire the agent has already read the cap's corrective
  * at least twice and re-issued regardless. One event read, no LLM.
  */
final class DuplicateRefusalGovernor(host: Sigil) extends TurnGovernor {
  override def name: String = "duplicate-refusal"

  override def evaluate(ctx: GovernorContext): Task[GovernorVote] =
    host.evaluateDuplicateRefusalLoop(ctx.conversationId, ctx.agent.id, ctx.claimed.timestamp.value).map {
      case Some((toolName, refusals)) =>
        GovernorVote.Intervene(
          Task.defer(host.publishInternalDirective(
            ctx.agent, ctx.conversation, Directive.DuplicateRefusalLoop(toolName, refusals))),
          Some(ForcedSynthesisReason.DuplicateRefusalLoop)
        )
      case None => GovernorVote.Proceed
    }
}
