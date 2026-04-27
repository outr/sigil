package sigil.script

import fabric.rw.*
import sigil.tool.ToolInput

/**
 * Input for [[DeleteScriptToolTool]] — remove an existing
 * [[ScriptTool]] from the catalog. Identified by `name`.
 */
case class DeleteScriptToolInput(name: String) extends ToolInput derives RW
