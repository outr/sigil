package spec

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import robobrowser.display.VirtualDisplayConfig
import robobrowser.stream.StreamConfig

/**
 * How a render request reaches the preview browser's virtual display.
 *
 * The display is a bound, not a knob: a stream captures a rectangle within
 * it and Xvfb refuses to resize a running display, so the launch size has
 * to cover both the request and whatever the app configured — otherwise a
 * portrait preview could never be resized back to landscape without
 * relaunching the browser.
 */
class StreamBrowserConfigSpec extends AnyWordSpec with Matchers {

  private val sigil = TestStreamBrowserSigil

  "streamBrowserConfigFor" should {

    "size the framebuffer from the declared max, not the first render target" in {
      sigil.usePreviewConfig(sigil.virtualDisplayPreviewConfig)
      val display = sigil.streamBrowserConfigFor(StreamConfig(
        width = Some(390),
        height = Some(844),
        maxWidth = Some(1920),
        maxHeight = Some(1080)
      )).virtualDisplay.get
      display.width should be >= 1920
      display.height should be >= 1080
    }

    "leave the configured display alone for an unsized request" in {
      sigil.usePreviewConfig(sigil.virtualDisplayPreviewConfig)
      sigil.streamBrowserConfigFor(StreamConfig()).virtualDisplay shouldBe
        Some(VirtualDisplayConfig(width = 1280, height = 720))
    }

    "grow the display to cover a portrait request in the dimension that needs it" in {
      sigil.usePreviewConfig(sigil.virtualDisplayPreviewConfig)
      val display = sigil.streamBrowserConfigFor(StreamConfig(width = Some(390), height = Some(844)))
        .virtualDisplay
        .getOrElse(fail("expected a virtual display"))
      // Width stays at the configured 1280 so a later resize back to
      // landscape still fits; height grows to hold the 844 target
      display.width shouldBe 1280
      display.height shouldBe 844
    }

    "keep the configured size when the request fits inside it" in {
      sigil.usePreviewConfig(sigil.virtualDisplayPreviewConfig)
      val display = sigil.streamBrowserConfigFor(StreamConfig(width = Some(640), height = Some(480)))
        .virtualDisplay
        .getOrElse(fail("expected a virtual display"))
      display.width shouldBe 1280
      display.height shouldBe 720
    }

    "carry the rest of the launch config through untouched" in {
      sigil.usePreviewConfig(sigil.virtualDisplayPreviewConfig)
      val sized = sigil.streamBrowserConfigFor(StreamConfig(width = Some(1920), height = Some(1080)))
      sized.browserConfig shouldBe sigil.virtualDisplayPreviewConfig.browserConfig
      sized.virtualDisplay.map(_.depth) shouldBe sigil.virtualDisplayPreviewConfig.virtualDisplay.map(_.depth)
    }

    "have nothing to size on a screencast-only host" in {
      sigil.usePreviewConfig(sigil.headlessPreviewConfig)
      try
        sigil.streamBrowserConfigFor(StreamConfig(width = Some(390), height = Some(844)))
          .virtualDisplay shouldBe None
      finally
        sigil.usePreviewConfig(sigil.headlessPreviewConfig)
    }
  }
}
