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
 *     deviating verdict; empty otherwise.
 *   - `objective` / `constraints` / `doneCriteria` / `currentPhase` —
 *     the plan fields: populated on the first review and on replan,
 *     echoed (with a refreshed `currentPhase`) otherwise.
 */
case class PlannerVerdictInput(verdict: String,
                               correction: String,
                               objective: String,
                               constraints: List[String],
                               doneCriteria: String,
                               currentPhase: String)
  extends ToolInput derives RW
