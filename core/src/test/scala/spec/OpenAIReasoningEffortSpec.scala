package spec

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import sigil.provider.{Effort, ReasoningMode}
import sigil.provider.openai.OpenAI

/**
 * Coverage for the model-registry-aware reasoning-effort resolution.
 * gpt-5 / o1 / o3 / o4 accept `minimal`; gpt-5.5 dropped `minimal`
 * for `none` and added `xhigh` at the high end. Hardcoding `"minimal"`
 * for `ReasoningMode.Off` produced HTTP 400 on gpt-5.5 and silently
 * disabled framework classifier consults; the family-aware resolution
 * picks the right token per model.
 */
class OpenAIReasoningEffortSpec extends AnyWordSpec with Matchers {

  "OpenAI.effortLevelsFromSlug" should {
    "return gpt-5.5's expanded set for gpt-5.5" in {
      OpenAI.effortLevelsFromSlug("gpt-5.5") shouldBe Set("none", "low", "medium", "high", "xhigh")
      OpenAI.effortLevelsFromSlug("openai/gpt-5.5") shouldBe Set("none", "low", "medium", "high", "xhigh")
      OpenAI.effortLevelsFromSlug("gpt-5_5-mini") shouldBe Set("none", "low", "medium", "high", "xhigh")
    }
    "return gpt-5.5's expanded set for gpt-5.4 (it also dropped minimal, added xhigh)" in {
      OpenAI.effortLevelsFromSlug("gpt-5.4-mini") shouldBe Set("none", "low", "medium", "high", "xhigh")
      OpenAI.effortLevelsFromSlug("openai/gpt-5.4") shouldBe Set("none", "low", "medium", "high", "xhigh")
      OpenAI.effortLevelsFromSlug("gpt-5_4-mini") shouldBe Set("none", "low", "medium", "high", "xhigh")
      OpenAI.effortLevelsFromSlug("gpt-5.4-mini") should not contain "minimal"
    }
    "return the legacy minimal-anchored set for gpt-5" in {
      OpenAI.effortLevelsFromSlug("gpt-5") shouldBe Set("minimal", "low", "medium", "high")
      OpenAI.effortLevelsFromSlug("openai/gpt-5") shouldBe Set("minimal", "low", "medium", "high")
    }
    "match gpt-5.5 before gpt-5 (longer-prefix-first)" in {
      OpenAI.effortLevelsFromSlug("gpt-5.5-mini") should contain("none")
      OpenAI.effortLevelsFromSlug("gpt-5.5-mini") should not contain "minimal"
    }
    "treat o-series models like gpt-5" in {
      OpenAI.effortLevelsFromSlug("o1-preview") should contain("minimal")
      OpenAI.effortLevelsFromSlug("o3") should contain("minimal")
      OpenAI.effortLevelsFromSlug("o4-mini") should contain("minimal")
    }
    "return empty for non-reasoning models" in {
      OpenAI.effortLevelsFromSlug("gpt-4o") shouldBe Set.empty
      OpenAI.effortLevelsFromSlug("openai/gpt-4-turbo") shouldBe Set.empty
    }
  }

  "OpenAI.lowestEffort / highestEffort" should {
    "pick none over minimal when both are available" in {
      OpenAI.lowestEffort(Set("none", "minimal", "low")) shouldBe "none"
    }
    "pick minimal when none isn't available (gpt-5 family)" in {
      OpenAI.lowestEffort(Set("minimal", "low", "medium", "high")) shouldBe "minimal"
    }
    "pick xhigh over high when both are available (gpt-5.5)" in {
      OpenAI.highestEffort(Set("none", "low", "medium", "high", "xhigh")) shouldBe "xhigh"
    }
    "pick high when xhigh isn't available (gpt-5)" in {
      OpenAI.highestEffort(Set("minimal", "low", "medium", "high")) shouldBe "high"
    }
    "fall back to legacy literal strings when the set is empty" in {
      OpenAI.lowestEffort(Set.empty) shouldBe "minimal"
      OpenAI.highestEffort(Set.empty) shouldBe "high"
    }
  }

  "OpenAI.effortLevelFor" should {
    "preserve Low / Medium / High when supported" in {
      val gpt55 = Set("none", "low", "medium", "high", "xhigh")
      OpenAI.effortLevelFor(Effort.Low, gpt55) shouldBe "low"
      OpenAI.effortLevelFor(Effort.Medium, gpt55) shouldBe "medium"
      OpenAI.effortLevelFor(Effort.High, gpt55) shouldBe "high"
    }
    "map Max to xhigh on gpt-5.5" in {
      val gpt55 = Set("none", "low", "medium", "high", "xhigh")
      OpenAI.effortLevelFor(Effort.Max, gpt55) shouldBe "xhigh"
    }
    "map Max to high on gpt-5 (no xhigh)" in {
      val gpt5 = Set("minimal", "low", "medium", "high")
      OpenAI.effortLevelFor(Effort.Max, gpt5) shouldBe "high"
    }
    "map Custom to the highest available level" in {
      OpenAI.effortLevelFor(Effort.Custom(99999), Set("none", "low", "medium", "high", "xhigh")) shouldBe "xhigh"
      OpenAI.effortLevelFor(Effort.Custom(99999), Set("minimal", "low", "medium", "high")) shouldBe "high"
    }
    "fall back to Effort.openAIEffortLevel when the set is empty" in {
      OpenAI.effortLevelFor(Effort.Low, Set.empty) shouldBe "low"
      OpenAI.effortLevelFor(Effort.Max, Set.empty) shouldBe "high"
    }
  }
}
