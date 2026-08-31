package sigil.workflow.tool

import fabric.rw.*
import sigil.tool.ToolOutput

/**
 * Structured result of `list_workflows` — the templates visible
 * to the caller, filtered by accessible spaces and (optionally) tag.
 */
case class ListWorkflowsOutput(workflows: List[WorkflowSummary]) extends ToolOutput derives RW
