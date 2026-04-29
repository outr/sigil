package sigil.browser.tool

import fabric.rw.*
import sigil.tool.ToolInput

/**
 * Input for `browser_navigate`. `waitForLoadSeconds` caps the time
 * spent waiting for the page's `load` event after the navigation —
 * default 15s matches RoboBrowser's typical reasonable wait.
 */
case class BrowserNavigateInput(url: String,
                                waitForLoadSeconds: Int = 15) extends ToolInput derives RW
