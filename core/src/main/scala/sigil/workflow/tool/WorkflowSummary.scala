package sigil.workflow.tool

import fabric.rw.*

/**
 * One workflow template projected into the `list_workflows` result.
 */
case class WorkflowSummary(workflowId: String,
                           name: String,
                           enabled: Boolean,
                           stepCount: Int,
                           description: Option[String])
  derives RW
