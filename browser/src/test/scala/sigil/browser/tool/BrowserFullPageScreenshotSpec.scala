package sigil.browser.tool

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import robobrowser.{BrowserConfig, RoboBrowser, RoboBrowserConfig}

/**
 * Live proof that `browser_screenshot`'s `fullPage` path captures the
 * whole scrollable page rather than just the visible viewport. Drives
 * [[BrowserScreenshotTool.captureFullPage]] against a real headless
 * Chrome showing a 4000px-tall document under an 800x600 viewport: the
 * captured PNG's height must exceed the viewport, proving the CDP
 * `captureBeyondViewport` + content-size clip took effect.
 *
 * Self-skips when Chrome/Chromium isn't installed.
 */
class BrowserFullPageScreenshotSpec extends AnyWordSpec with Matchers {

  private val chromeAvailable: Boolean =
    List(
      "/usr/bin/google-chrome",
      "/usr/bin/google-chrome-stable",
      "/usr/bin/chromium",
      "/usr/local/bin/google-chrome")
      .exists(p => new java.io.File(p).canExecute)

  // A document far taller than the viewport so a viewport-only capture
  // and a full-page capture have unmistakably different heights. No `#`
  // colors — `#` is the data-URL fragment delimiter and would truncate
  // the HTML before the div renders.
  private val tallPage =
    "data:text/html,<html><body style='margin:0'>" +
      "<div style='height:4000px;width:100%;background:red'></div>" +
      "</body></html>"

  private def beInt(bytes: Array[Byte], off: Int): Int = ((bytes(off) & 0xff) << 24) | ((bytes(off + 1) & 0xff) << 16) |
    ((bytes(off + 2) & 0xff) << 8) | (bytes(off + 3) & 0xff)
  // PNG IHDR: width at byte offset 16, height at 20 (big-endian).
  private def pngWidth(bytes: Array[Byte]): Int = beInt(bytes, 16)
  private def pngHeight(bytes: Array[Byte]): Int = beInt(bytes, 20)

  "BrowserScreenshotTool.captureFullPage" should {
    "capture the entire scrollable page, well beyond the viewport" in {
      if (!chromeAvailable) cancel("Chrome/Chromium not installed — live browser test")
      val browser = RoboBrowser(
        RoboBrowserConfig(browserConfig = BrowserConfig(headless = true, disableGPU = true))
      ).sync()
      try {
        browser.navigate(tallPage).sync()
        browser.setViewportSize(800, 600).sync()
        val bytes = BrowserScreenshotTool.captureFullPage(browser).sync()
        bytes.length should be > 8
        // Width tracks the 800px viewport; height captures the full
        // ~4000px content, far beyond the 600px viewport.
        pngWidth(bytes) should (be >= 600 and be <= 900)
        pngHeight(bytes) should be > 2000
      } finally browser.dispose().sync()
    }
  }
}
