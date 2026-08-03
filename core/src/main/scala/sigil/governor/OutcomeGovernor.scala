package sigil.governor

import rapid.Task
import sigil.Sigil

/** A guard consulted once per iteration, at the moment the provider
  * stream closes — the sibling of [[TurnGovernor]] for verdicts that must
  * ride the turn's own signal stream instead of the boundary after it.
  *
  * Two properties force these guards to sit here rather than at the
  * boundary:
  *
  *   - Their diagnostics ARE the loop's continuation signal. A Tool-role
  *     Message published inside the iteration re-triggers the agent
  *     (`TriggerFilter` always re-fires on Tool role); the same Message
  *     published at the boundary arrives after the loop has already
  *     decided whether to continue, so a turn that should have iterated
  *     would instead end with the diagnostic unread.
  *   - One of them redirects what the turn publishes: the naked-text
  *     decision chooses between settling the streamed prose as the
  *     terminal reply and settling it as a progress message plus a
  *     challenge. There is no boundary-shaped vote for "publish this
  *     event differently."
  *
  * [[sigil.Sigil.outcomeGovernors]] is folded in list order and EVERY
  * governor is consulted — unlike the boundary fold, these verdicts are
  * complementary rather than competing (a `max_tokens` turn can be both a
  * repetition loop and a dropped plain-text reply), so emissions
  * concatenate in list order. Order is therefore emission order.
  */
trait OutcomeGovernor {

  /** Stable identifier, used in logs and for apps that filter or reorder
    * the default list. */
  def name: String

  /** Decide what this drained iteration should additionally publish. */
  def evaluate(outcome: TurnOutcome, host: Sigil): Task[OutcomeVerdict]
}
