package spec

import fabric.rw.*
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import sigil.browser.tool.BrowserScreenshotTool
import sigil.tool.ImageToolOutput

/**
 * Regression for the blind-screenshot bug: `browser_screenshot` used to
 * resolve to a bespoke text `BrowserScreenshotOutput(fileId, url,
 * altText)`. A text output is NOT lifted by `FrameBuilder` into the
 * frame's `images` list, so the captured PNG never reached the agent's
 * visual context — the agent saw only a URL it can't fetch and re-shot
 * the page indefinitely trying to "get" an image that was never
 * delivered.
 *
 * The fix routes the capture through the framework's [[ImageToolOutput]]
 * (Sigil #280), whose URL the FrameBuilder lifts into the next turn's
 * visual context (proven end-to-end in core's ImageToolOutputSpec). This
 * pins the type binding so a future change can't silently revert the
 * tool to a blind text output.
 */
class BrowserScreenshotImageOutputSpec extends AnyWordSpec with Matchers {

  "BrowserScreenshotTool" should {
    "declare ImageToolOutput so the capture is lifted into the agent's visual context, not returned as a blind reference" in {
      val tool = new BrowserScreenshotTool
      // Compile-time: the tool's `Output` is exactly ImageToolOutput. A
      // text output would not type-check here (and would not be lifted).
      val _: RW[ImageToolOutput] = tool.outputRW
      // Runtime: the declared output schema is ImageToolOutput's, not a
      // bespoke {fileId, url, altText} text shape.
      tool.outputRW.definition shouldBe summon[RW[ImageToolOutput]].definition
    }
  }
}
