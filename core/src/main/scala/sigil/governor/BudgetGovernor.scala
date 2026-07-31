package sigil.governor

import rapid.Task
import sigil.{ForcedSynthesisReason, Sigil}

/** Spend-budget guard — evaluated at EVERY boundary (a dollar-a-minute
  * turn must not wait for a checkpoint interval), and first in the
  * default [[sigil.Sigil.turnGovernors]] order so the hard ceiling
  * preempts everything.
  *
  * The hard ceiling publishes its directive and forces one wrap-up
  * iteration reporting spend and state. The soft check-in is a one-shot
  * cooperative nudge on the same Tool-role channel as stall directives;
  * the agent summarizes and asks the user via `respond_options`, and the
  * continuation is a fresh turn (fresh budget, fresh classification).
  */
final class BudgetGovernor(host: Sigil) extends TurnGovernor {
  override def name: String = "budget"

  override def evaluate(ctx: GovernorContext): Task[GovernorVote] =
    host.evaluateBudgetGate(ctx.conversation, ctx.claimed).map {
      case Some(directive) if directive.hard =>
        GovernorVote.Intervene(
          host.publishInternalDirective(ctx.agent, ctx.conversation, directive.directive),
          Some(ForcedSynthesisReason.BudgetCeiling)
        )
      case Some(directive) =>
        GovernorVote.Intervene(
          host.publishInternalDirective(ctx.agent, ctx.conversation, directive.directive),
          None
        )
      case None => GovernorVote.Proceed
    }
}
