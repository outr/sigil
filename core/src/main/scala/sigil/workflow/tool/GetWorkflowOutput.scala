package sigil.workflow.tool

import fabric.rw.*
import sigil.tool.ToolOutput

/**
 * Structured result of `get_workflow` — the fetched template's
 * descriptive shape, or a not-found / not-authorized signal.
 */
enum GetWorkflowOutput extends ToolOutput derives RW {

  /**
   * The template was found and the caller is authorized for its space.
   */
  case Found(workflowId: String,
             name: String,
             enabled: Boolean,
             description: Option[String],
             space: String,
             stepIds: List[String],
             triggerKinds: List[String],
             variables: List[GetWorkflowVariable],
             tags: List[String])

  /**
   * No template with the given id is visible to the caller — either it
   * doesn't exist or it lives in an inaccessible space (existence is
   * hidden across space boundaries).
   */
  case NotFound(workflowId: String)
}
