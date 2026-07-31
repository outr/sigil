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
  * Two things deliberately stay outside this seam:
  *
  *   - The `maxAgentIterations` cap. It is not a boundary vote but the
  *     loop's own match guard: its else-branches carry the cap-hit
  *     forced synthesis, the no-tool-call full-roster retry, and the
  *     worker-rest path — recoveries with continuation shapes a vote
  *     cannot express.
  *   - The orchestrator's mid-stream intercepts (refusal challenge,
  *     duplicate-call suppression, turn-decision challenge). Those fire
  *     while the provider stream is still open, rewriting what the model
  *     just emitted; a governor only ever sees a fully drained iteration.
  */
trait TurnGovernor {

  /** Stable identifier, used in logs and for apps that filter or reorder
    * the default list. */
  def name: String

  /** Decide what should happen at this boundary. */
  def evaluate(ctx: GovernorContext): Task[GovernorVote]
}
