package sigil.browser.tool

import fabric.rw.*
import sigil.tool.ToolInput

/** Input for `browser_type`. Types `value` into the element matched
  * by `selector`. `clearFirst` clears the field before typing —
  * default `true` so re-runs don't append to existing text. */
case class BrowserTypeInput(selector: String,
                            value: String,
                            clearFirst: Boolean = true) extends ToolInput derives RW
