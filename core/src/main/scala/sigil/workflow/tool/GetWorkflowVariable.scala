package sigil.workflow.tool

import fabric.rw.*

/**
 * A workflow template's declared variable, projected for tool output.
 */
case class GetWorkflowVariable(name: String, required: Boolean) derives RW
