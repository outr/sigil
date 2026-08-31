package sigil.browser.tool

import fabric.rw.*
import sigil.browser.{ScrollAmount, ScrollDirection}
import sigil.tool.ToolInput

/**
 * Input for `browser_scroll`. `direction` chooses up / down; `amount`
 * chooses a one-viewport page move or an absolute top / bottom jump.
 */
case class BrowserScrollInput(direction: ScrollDirection = ScrollDirection.Down,
                              amount: ScrollAmount = ScrollAmount.Page)
  extends ToolInput derives RW
