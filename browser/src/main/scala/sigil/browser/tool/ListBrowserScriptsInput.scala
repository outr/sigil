package sigil.browser.tool

import fabric.rw.*
import sigil.tool.ToolInput

/** Args for [[ListBrowserScriptsTool]] — no filters today; returns
  * every script the caller's `accessibleSpaces` allows. */
case class ListBrowserScriptsInput() extends ToolInput derives RW
