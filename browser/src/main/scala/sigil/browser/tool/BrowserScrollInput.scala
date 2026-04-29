package sigil.browser.tool

import fabric.rw.*
import sigil.tool.ToolInput

/** Input for `browser_scroll`. `direction` is `"up"` or `"down"`.
  * `amount` is `"page"` (one viewport), `"top"` (jump to top), or
  * `"bottom"` (jump to bottom). */
case class BrowserScrollInput(direction: String = "down",
                              amount: String = "page") extends ToolInput derives RW
