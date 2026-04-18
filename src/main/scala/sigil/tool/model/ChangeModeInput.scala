package sigil.tool.model

import fabric.rw.*
import sigil.provider.Mode
import sigil.tool.ToolInput

/**
 * Input for the change_mode tool. The model calls this to transition the
 * agent into a different operating mode — e.g., from Conversation to Coding
 * when the user's intent shifts.
 */
case class ChangeModeInput(mode: Mode, reason: Option[String] = None) extends ToolInput derives RW
