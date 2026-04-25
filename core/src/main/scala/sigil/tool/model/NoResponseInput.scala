package sigil.tool.model

import fabric.rw.*
import sigil.tool.ToolInput

/**
 * Input for the no_response tool. The agent calls this to end its turn without
 * producing any user-visible content — when the current message isn't one it
 * should respond to per its personality/role, or when another participant is
 * better suited to reply.
 *
 * `reason` is optional free-form prose for audit/telemetry; it is not shown to
 * the user.
 */
case class NoResponseInput(reason: Option[String] = None) extends ToolInput derives RW
