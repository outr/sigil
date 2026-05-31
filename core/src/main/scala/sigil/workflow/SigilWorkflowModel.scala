package sigil.workflow

import fabric.rw.*
import strider.AbstractWorkflowModel
import strider.step.{Approval, Condition, Job, Loop, Parallel, Recycle, Step, SubWorkflow, Trigger}

/**
 * Concrete Strider [[AbstractWorkflowModel]] for the Sigil-side
 * workflow runtime. Declares the polymorphic `stepRW` that
 * round-trips every concrete `Step` subclass the framework can
 * emit when compiling [[WorkflowStepInput]]s.
 *
 * The `RW.poly` registration here is what unlocks Strider's
 * persistence: when the engine writes a `Workflow` row to disk it
 * encodes each `Step` via this poly, and on rehydrate it dispatches
 * back to the right concrete class. Apps that ship their own
 * Strider step subclasses (custom `Job` types, etc.) extend this
 * model and add their RWs.
 */
object SigilWorkflowModel extends AbstractWorkflowModel {
  // Lazy because some entries (SubWorkflow) reference `RW[List[Step]]`
  // which closes back over `stepRW` itself — fabric's poly resolution
  // is lazy, so circularity is fine as long as the val isn't forced
  // at construction time.
  override implicit lazy val stepRW: RW[Step] = RW.poly[Step]()(
    summon[RW[SigilJobStep]],
    summon[RW[SigilCondition]],
    summon[RW[SigilApproval]],
    summon[RW[Parallel]],
    summon[RW[Loop]],
    summon[RW[Recycle]],
    SubWorkflow.rw,
    summon[RW[sigil.workflow.trigger.ConversationMessageTriggerImpl]],
    summon[RW[sigil.workflow.trigger.TimeTriggerImpl]],
    summon[RW[sigil.workflow.trigger.WebhookTriggerImpl]],
    summon[RW[sigil.workflow.trigger.WorkflowEventTriggerImpl]]
  )
}
