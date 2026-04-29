package sigil.browser.tool

import fabric.rw.*
import sigil.tool.ToolInput

/** Input for `browser_click`. `selector` is a CSS selector matching
  * a single element; clicking the first match. */
case class BrowserClickInput(selector: String) extends ToolInput derives RW
