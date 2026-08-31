package sigil.tool.consult

import fabric.rw.*
import sigil.tool.ToolInput

/**
 * Typed shape for the planner-tier checkpoint verdict. The framework
 * dispatches a one-shot LLM call to the configured planner model,
 * which holds the turn's plan and judges the executor's trajectory
 * against it.
 *
 *   - `verdict` — "on_track" (work is converging on the plan's done
 *     criteria), "deviating" (executor has lost the plot; a
 *     correction directive is required), or "replan" (the plan itself
 *     no longer fits; the returned plan fields are the revision).
 *   - `correction` — the directive published to the executor on a
 *     deviating verdict; omitted otherwise.
 *   - `currentPhase` — where the work stands now; refreshed on every
 *     review.
 *   - `objective` / `constraints` / `doneCriteria` — the plan fields:
 *     populated on the first review (which creates the plan) and on
 *     replan (the revision). Omitted on every other verdict — the
 *     framework retains the plan it showed the model, so re-emitting
 *     these would only spend (and, for a long objective, overflow)
 *     the consult's output budget.
 */
case class PlannerVerdictInput(verdict: String,
                               correction: Option[String] = None,
                               currentPhase: String,
                               objective: Option[String] = None,
                               constraints: List[String] = Nil,
                               doneCriteria: Option[String] = None)
  extends ToolInput derives RW
