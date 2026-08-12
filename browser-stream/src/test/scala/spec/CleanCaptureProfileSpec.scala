package spec

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

/**
 * The stream browser's default profile must not paint Chrome's own UI
 * into the capture: no save-password bubble (profile-level
 * suppression, not --enable-automation, so no automation infobar
 * either), --test-type for the remaining first-run/security prompts,
 * translate and breach-check features off, crash-restore and
 * first-run bubbles suppressed. Pooled headless automation browsers
 * keep their unmodified profile.
 */
class CleanCaptureProfileSpec extends AnyWordSpec with Matchers {

  private val stream = TestStreamBrowserSigil.defaultStreamBrowserConfig.browserConfig
  private val pooled = TestStreamBrowserSigil.browserConfig.browserConfig

  "streamBrowserConfig" should {

    "suppress the password manager at the profile level without --enable-automation" in {
      stream.passwordManager shouldBe false
      stream.options should not contain "--enable-automation"
      stream.options.filter(_.contains("incognito")) shouldBe empty
    }

    "send --test-type and the bubble-suppression switches" in {
      stream.options should contain("--test-type")
      stream.options should contain("--hide-crash-restore-bubble")
      stream.options should contain("--no-first-run")
      stream.options should contain("--no-default-browser-check")
    }

    "disable the translate and password-breach features" in {
      val features = stream.options.find(_.startsWith("--disable-features")).getOrElse("")
      features should include("Translate")
      features should include("PasswordLeakDetection")
    }

    "be headful while the pooled automation config stays headless and untouched" in {
      stream.headless shouldBe false
      pooled.headless shouldBe true
      pooled.passwordManager shouldBe true
      pooled.options should not contain "--test-type"
    }
  }
}
