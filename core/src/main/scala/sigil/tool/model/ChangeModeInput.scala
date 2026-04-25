package sigil.tool.model

import fabric.rw.*
import sigil.tool.ToolInput

/**
 * Input for the change_mode tool. The model calls this to transition
 * the agent into a different operating mode. `mode` is the target
 * mode's stable `name` — the framework resolves it to a registered
 * [[sigil.provider.Mode]] instance at call time via
 * `Sigil.modeByName`.
 */
case class ChangeModeInput(mode: String, reason: Option[String] = None) extends ToolInput derives RW
