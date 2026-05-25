package spec

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import sigil.provider.{GenerationSettings, OutputTokenCap}

/**
 * Sigil bug #276 — resolve `max_tokens` from the Model registry instead
 * of letting consumers hardcode model-specific ceilings.
 *
 * Three properties verified at the type level (the AnthropicProvider
 * runtime resolution is exercised by [[LlamaCppProviderSpec]] / live
 * provider specs against real model records):
 *
 *   1. [[OutputTokenCap.ModelMax]] is the default — no caller has to
 *      think about ceilings unless they actually want one.
 *   2. The deprecated `maxOutputTokens: Option[Int]` shim resolves
 *      through [[GenerationSettings.effectiveCap]] to the same typed
 *      shape, so pre-fix call sites keep their behaviour during the
 *      deprecation window.
 *   3. [[GenerationSettings.tightenedTo]] preserves a tighter caller-
 *      supplied cap (idempotent) and lowers a looser one — the
 *      paraphrase-loop / forced-synthesis tightener depends on this.
 */
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
        outputTokenCap  = OutputTokenCap.Below(500),
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

  "Anthropic known-ceilings seed (sigil #276)" should {

    "carry the published max_tokens for current Anthropic catalog models" in {
      import sigil.provider.anthropic.Anthropic
      // Spot-check the values the bug doc cited. The map is meant to be
      // kept in sync with Anthropic's public docs; this assertion serves
      // as the canonical reference for the test fixture.
      Anthropic.KnownMaxCompletionTokens.get("claude-haiku-4-5-20251001") shouldBe Some(64000)
      Anthropic.KnownMaxCompletionTokens.get("claude-sonnet-4-6")         shouldBe Some(64000)
      Anthropic.KnownMaxCompletionTokens.get("claude-opus-4-7")           shouldBe Some(32000)
    }
  }
}
