package sigil.conversation

import fabric.rw.*

/**
 * Turn-scoped plan artifact held by the planner-tier checkpoint.
 * Created (and revised) by the planner consult, kept in the per-claim
 * checkpoint state, and published into the conversation as an
 * Agents-visibility directive so the executor sees the plan it is
 * being judged against.
 *
 *   - `objective` — what this turn's work must deliver.
 *   - `constraints` — hard boundaries the executor must not cross.
 *   - `doneCriteria` — how anyone can tell the work is finished.
 *   - `currentPhase` — where the work stands right now, refreshed on
 *     each planner review.
 */
case class TurnPlan(objective: String,
                    constraints: List[String],
                    doneCriteria: String,
                    currentPhase: Option[String])
  derives RW
