package sigil.browser.tool

import fabric.{Json, Obj}
import fabric.rw.*
import sigil.browser.{BrowserStep, CookieJar}
import sigil.tool.ToolInput
import lightdb.id.Id

/**
 * Args for [[CreateBrowserScriptTool]]. The agent supplies the
 * script's surface (name, description, parameters JSON Schema) plus
 * the literal step list and an optional cookie-jar reference for
 * resume-with-login flows.
 */
case class CreateBrowserScriptInput(name: String,
                                    description: String,
                                    parameters: Json = Obj.empty,
                                    steps: List[BrowserStep],
                                    space: Option[String] = None,
                                    keywords: Set[String] = Set.empty,
                                    cookieJarId: Option[String] = None) extends ToolInput derives RW
