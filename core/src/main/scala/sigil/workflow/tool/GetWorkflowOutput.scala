package sigil.workflow.tool

import fabric.rw.*
import sigil.tool.ToolOutput
import sigil.workflow.{WorkflowStepSpec, WorkflowTrigger}

/**
 * Structured result of `get_workflow` — the fetched template's
 * descriptive shape, or a not-found / not-authorized signal.
 */
enum GetWorkflowOutput extends ToolOutput derives RW {

  /** The template was found and the caller is authorized for its space.
    *
    * `steps` and `triggers` carry the full step / trigger bodies in the
    * same flat [[WorkflowStepSpec]] / [[WorkflowTrigger]] shapes that
    * `create_workflow` / `update_workflow` consume, so a fetched template
    * round-trips directly back into an edit. */
  case Found(workflowId: String,
             name: String,
             enabled: Boolean,
             description: Option[String],
             space: String,
             steps: List[WorkflowStepSpec],
             triggers: List[WorkflowTrigger],
             variables: List[GetWorkflowVariable],
             tags: List[String])

  /** No template with the given id is visible to the caller — either it
    * doesn't exist or it lives in an inaccessible space (existence is
    * hidden across space boundaries). */
  case NotFound(workflowId: String)
}
