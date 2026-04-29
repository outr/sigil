package sigil.browser.tool

import fabric.rw.*
import sigil.tool.ToolInput

/** Input for `browser_screenshot`. `waitSeconds` lets the agent
  * pause before capture (e.g. for animations / lazy images to
  * settle). `maxWidth` / `maxHeight` cap the captured viewport
  * dimensions; `None` keeps the browser's current viewport. */
case class BrowserScreenshotInput(waitSeconds: Int = 2,
                                  maxWidth: Option[Int] = None,
                                  maxHeight: Option[Int] = None) extends ToolInput derives RW
