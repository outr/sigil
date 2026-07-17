package sigil.browser.tool

import fabric.rw.*
import sigil.tool.ToolInput

/**
 * Input for `browser_screenshot`. `fullPage` captures the entire
 * scrollable page (sized to the document's CSS content box, via CDP
 * `captureBeyondViewport`) instead of just the visible viewport.
 * `waitSeconds` lets the agent pause before capture (e.g. for
 * animations / lazy images to settle). `maxWidth` / `maxHeight` cap
 * the captured viewport dimensions; `None` keeps the browser's
 * current viewport (ignored under `fullPage`, which sizes itself to
 * the content).
 */
case class BrowserScreenshotInput(fullPage: Boolean = false,
                                  waitSeconds: Int = 2,
                                  maxWidth: Option[Int] = None,
                                  maxHeight: Option[Int] = None)
  extends ToolInput derives RW
