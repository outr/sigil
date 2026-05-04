package sigil.workflow

import fabric.rw.*

/**
 * Step that pauses the workflow until an external trigger fires.
 * Wraps a [[WorkflowTrigger]] — the typed Sigil-side shape — which
 * compiles to a `strider.step.Trigger` at scheduling time. The
 * compiled trigger owns its own `mode` / `timeoutMs` / `timeoutAction`
 * (Strider's [[strider.step.TriggerMode]] / [[strider.step.TimeoutAction]]),
 * so any tuning of fire / wait / timeout behaviour is configured on
 * the [[WorkflowTrigger]] itself rather than at this step.
 */
case class TriggerStepInput(id: String,
                            trigger: WorkflowTrigger,
                            name: Option[String] = None,
                            output: Option[String] = None) extends WorkflowStepInput derives RW
