package spec

import fabric.rw.*
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import sigil.browser.tool.BrowserScreenshotTool
import sigil.tool.ImageToolOutput

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
