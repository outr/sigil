package sigil.tool.context

import fabric.rw.*
import sigil.tool.ToolInput

/**
 * Input for the `context_breakdown` tool — no parameters; the tool
 * inspects the current turn's accumulated state and returns the
 * profile breakdown the agent can describe to the user.
 */
case class ContextBreakdownInput() extends ToolInput derives RW
