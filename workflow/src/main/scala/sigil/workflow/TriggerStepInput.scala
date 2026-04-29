package sigil.workflow

import fabric.rw.*

/**
 * Step that pauses the workflow until an external trigger fires.
 * Wraps a [[WorkflowTrigger]] — the typed Sigil-side shape — which
 * compiles to a `strider.step.Trigger` at scheduling time.
 *
 * `mode = "continue"` resumes the same workflow on every firing.
 * `mode = "branch"` clones the workflow at the trigger point on
 * every firing — ideal for recurring schedules where each tick
 * should run independently.
 *
 * `timeoutMs` (optional) bounds how long the workflow waits before
 * `timeoutAction` fires. `timeoutAction = "fail"` fails the run;
 * `"proceed"` completes the trigger as if it fired with an empty
 * payload; `"branch:<stepId>"` jumps to a different step.
 */
case class TriggerStepInput(id: String,
                            name: String = "",
                            trigger: WorkflowTrigger,
                            mode: String = "continue",
                            output: String = "",
                            timeoutMs: Option[Long] = None,
                            timeoutAction: String = "fail") extends WorkflowStepInput derives RW
