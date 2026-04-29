package sigil.browser.tool

import fabric.{Json, Obj}
import fabric.rw.*
import sigil.browser.BrowserStep
import sigil.tool.ToolInput

/** Args for [[UpdateBrowserScriptTool]]. `name` selects the script
  * to update by name. Each non-`None` field replaces the
  * corresponding field on the stored script. */
case class UpdateBrowserScriptInput(name: String,
                                    description: Option[String] = None,
                                    parameters: Option[Json] = None,
                                    steps: Option[List[BrowserStep]] = None,
                                    keywords: Option[Set[String]] = None,
                                    cookieJarId: Option[String] = None) extends ToolInput derives RW
