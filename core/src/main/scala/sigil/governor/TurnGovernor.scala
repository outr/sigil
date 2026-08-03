package sigil.governor

import rapid.Task

/** A guard consulted at every agent-loop iteration boundary — after the
  * iteration drains and the loop has decided to keep going, before the
  * next iteration is dispatched.
  *
  * The loop folds [[sigil.Sigil.turnGovernors]] in list order and the
  * FIRST non-[[GovernorVote.Proceed]] vote wins; later governors are not
  * evaluated at that boundary. Order therefore encodes precedence, and
  * evaluation is lazy — a governor whose check is expensive (an LLM
  * reflection, a DB scan) pays nothing at a boundary an earlier governor
  * already claimed.
  *
  * That precedence is absolute, so a custom governor PREPENDED to the
  * default list preempts every built-in at any boundary it claims —
  * including the hard spend ceiling, which is first by default precisely
  * because a runaway turn must not wait behind anything. Append after
  * the defaults (`super.turnGovernors :+ mine`) unless preemption is
  * exactly the intent.
  *
  * Two things deliberately stay outside this seam:
  *
  *   - The `maxAgentIterations` cap. It is not a boundary vote but the
  *     loop's own match guard: its else-branches carry the cap-hit
  *     forced synthesis, the no-tool-call full-roster retry, and the
  *     worker-rest path — recoveries with continuation shapes a vote
  *     cannot express.
  *   - The orchestrator's mid-stream intercepts — the refusal challenge
  *     and the repeated-query suppression. Those fire while the provider
  *     stream is still open, rewriting a call the model is in the middle
  *     of making; a governor only ever sees a fully drained iteration.
  *
  * The guards that read a drained iteration but must publish INTO it
  * rather than at the boundary after it are
  * [[OutcomeGovernor]]s — same arbitration seam, different attachment
  * point.
  */
trait TurnGovernor {

  /** Stable identifier, used in logs and for apps that filter or reorder
    * the default list. */
  def name: String

  /** Decide what should happen at this boundary. */
  def evaluate(ctx: GovernorContext): Task[GovernorVote]
}
