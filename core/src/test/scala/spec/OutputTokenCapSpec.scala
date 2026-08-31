package spec

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import sigil.provider.{GenerationSettings, OutputTokenCap}

class OutputTokenCapSpec extends AnyWordSpec with Matchers {

  "GenerationSettings.outputTokenCap (sigil #276)" should {

    "default to OutputTokenCap.ModelMax" in {
      GenerationSettings().outputTokenCap shouldBe OutputTokenCap.ModelMax
      GenerationSettings().effectiveCap shouldBe OutputTokenCap.ModelMax
    }

    "round-trip the new typed form via effectiveCap" in {
      val gen = GenerationSettings(outputTokenCap = OutputTokenCap.Below(1500))
      gen.outputTokenCap shouldBe OutputTokenCap.Below(1500)
      gen.effectiveCap shouldBe OutputTokenCap.Below(1500)
      // The legacy field is None when the new field is set directly.
      (gen.maxOutputTokens: @annotation.nowarn("cat=deprecation")) shouldBe None
      gen.explicitWireMaxTokens shouldBe Some(1500)
    }

    "honour the deprecated maxOutputTokens via the compat shim" in {
      @annotation.nowarn("cat=deprecation")
      val gen = GenerationSettings(maxOutputTokens = Some(2000))
      gen.effectiveCap shouldBe OutputTokenCap.Below(2000)
      gen.explicitWireMaxTokens shouldBe Some(2000)
    }

    "let the deprecated field win when both are set (back-compat for pre-#276 callers)" in {
      @annotation.nowarn("cat=deprecation")
      val gen = GenerationSettings(
        outputTokenCap = OutputTokenCap.Below(500),
        maxOutputTokens = Some(2000)
      )
      gen.effectiveCap shouldBe OutputTokenCap.Below(2000)
    }
  }

  "GenerationSettings.tightenedTo (sigil #276)" should {

    "tighten ModelMax down to Below(n)" in {
      GenerationSettings().tightenedTo(2048).effectiveCap shouldBe OutputTokenCap.Below(2048)
    }

    "preserve a tighter caller-supplied cap (no-op when current <= n)" in {
      val tight = GenerationSettings(outputTokenCap = OutputTokenCap.Below(500))
      tight.tightenedTo(2048).effectiveCap shouldBe OutputTokenCap.Below(500)
    }

    "lower a looser caller-supplied cap" in {
      val loose = GenerationSettings(outputTokenCap = OutputTokenCap.Below(10000))
      loose.tightenedTo(2048).effectiveCap shouldBe OutputTokenCap.Below(2048)
    }

    "tighten a deprecated maxOutputTokens through the compat shim" in {
      @annotation.nowarn("cat=deprecation")
      val legacyLoose = GenerationSettings(maxOutputTokens = Some(10000))
      legacyLoose.tightenedTo(2048).effectiveCap shouldBe OutputTokenCap.Below(2048)
    }
  }

}
