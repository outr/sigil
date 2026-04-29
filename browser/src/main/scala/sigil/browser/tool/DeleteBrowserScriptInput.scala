package sigil.browser.tool

import fabric.rw.*
import sigil.tool.ToolInput

/** Args for [[DeleteBrowserScriptTool]]. */
case class DeleteBrowserScriptInput(name: String) extends ToolInput derives RW
