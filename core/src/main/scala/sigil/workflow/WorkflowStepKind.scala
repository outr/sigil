package sigil.workflow

import fabric.rw.*

/**
 * The shape of a single [[WorkflowStepSpec]]. A flat string discriminator the
 * model fills on the model-facing authoring surface (`create_workflow` /
 * `update_workflow`), in place of selecting a nested union variant. Each case
 * lowers to the matching [[WorkflowStepInput]] IR (see [[WorkflowStepSpec.lower]]).
 */
enum WorkflowStepKind derives RW {

  /**
   * Run one tool or LLM prompt; capture the result in `output`.
   */
  case Job

  /**
   * Branch on a variable expression to one of two step ids.
   */
  case Condition

  /**
   * Pause for a human gate; resumed via `resume_workflow`.
   */
  case Approval

  /**
   * Fork into branches (each a list of step ids) and join.
   */
  case Parallel

  /**
   * Iterate body steps once per element of an `over` variable.
   */
  case Loop

  /**
   * Invoke another persisted workflow by id.
   */
  case SubWorkflow

  /**
   * Pause until an external trigger fires.
   */
  case Trigger
}
