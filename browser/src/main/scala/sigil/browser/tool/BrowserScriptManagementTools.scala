package sigil.browser.tool

import sigil.tool.{Tool, ToolName}

/** Convenience aggregator for the four BrowserScript management
  * tools. Apps that want to expose script authoring to the agent
  * extend their `staticTools` with this list:
  *
  * {{{
  *   override def staticTools: List[Tool] =
  *     super.staticTools ++ BrowserCoreTools.all ++ BrowserScriptManagementTools.all
  * }}}
  *
  * Apps that want only execution (not authoring) skip this list. */
object BrowserScriptManagementTools {
  val all: List[Tool] = List(
    CreateBrowserScriptTool,
    UpdateBrowserScriptTool,
    DeleteBrowserScriptTool,
    ListBrowserScriptsTool
  )

  val toolNames: List[ToolName] = all.map(_.name)
}
