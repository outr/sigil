package sigil.governor

import rapid.Task
import sigil.ForcedSynthesisReason

/** A [[TurnGovernor]]'s verdict for one agent-loop iteration boundary.
  *
  * The two shapes cover every boundary behavior the loop has:
  *
  *   - [[GovernorVote.Proceed]] — this governor sees nothing to act on;
  *     the loop consults the next governor in [[sigil.Sigil.turnGovernors]]
  *     and, if all proceed, runs the next iteration normally
  *     (intra-turn compaction, then recursion).
  *   - [[GovernorVote.Intervene]] — this governor acts. `publish` is run
  *     inside the iteration's batched-event scope, before the loop's
  *     continuation is chosen; `forceSynthesis` then selects between the
  *     two continuations the loop supports: `None` continues the loop
  *     normally (compaction + next iteration), `Some(reason)` runs the
  *     single forced-synthesis wrap-up iteration with `tool_choice`
  *     pinned to the respond family.
  *
  * The publish-then-choose split matters: directives must land inside the
  * batched scope so the next iteration reads them, while the continuation
  * must run only after that batch commits.
  */
enum GovernorVote {

  /** No intervention from this governor at this boundary. */
  case Proceed

  /** Publish `publish` at this boundary, then continue the loop
    * (`forceSynthesis = None`) or force the terminal synthesis
    * iteration (`forceSynthesis = Some(reason)`). */
  case Intervene(publish: Task[Unit], forceSynthesis: Option[ForcedSynthesisReason])
}
