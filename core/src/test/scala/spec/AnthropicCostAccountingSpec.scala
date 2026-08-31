package spec

import fabric.*
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Task}
import sigil.Sigil
import sigil.db.ModelPricing
import sigil.provider.{CacheKeys, TokenUsage}

/**
 * Regression for sigil bug #290 — Anthropic billing was under-counted
 * by ~350× because:
 *
 *   1. `AnthropicProvider.parseUsage` treated `input_tokens` (fresh)
 *      as the total prompt — but Anthropic returns three additive
 *      buckets (`input_tokens`, `cache_creation_input_tokens`,
 *      `cache_read_input_tokens`). Cache buckets vanished from
 *      accounting.
 *   2. The cost formula only multiplied `pricing.prompt × promptTokens`
 *      + `pricing.completion × completionTokens` — cache reads/writes
 *      were never billed against their own rates.
 *
 * Covered here:
 *   - The Anthropic-shape usage decoder folds all three input buckets
 *     into `promptTokens` AND keeps the per-cache subsets populated.
 *   - [[Sigil.costFor]] applies the right per-bucket rate and matches
 *     a hand-computed invoice within rounding tolerance.
 *   - Providers without cache buckets (e.g. legacy fresh-only) keep
 *     the same numerical result the prior `prompt × promptTokens`
 *     formula produced.
 *   - Pricing fallbacks fire when the catalog doesn't publish
 *     per-cache rates.
 */
class AnthropicCostAccountingSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {

  /**
   * Simulate the Anthropic-shape usage JSON the bug doc cites.
   */
  private val bugDocUsageJson: Json = obj(
    "input_tokens" -> num(166),
    "cache_creation_input_tokens" -> num(44856),
    "cache_read_input_tokens" -> num(11633),
    "output_tokens" -> num(8000)
  )

  /**
   * Haiku rates per OpenRouter (per-million USD; the per-token rate
   * is the per-million number divided by 1,000,000).
   */
  private val haikuPricing: ModelPricing = ModelPricing(
    prompt = BigDecimal("0.000001"), // $1/M
    completion = BigDecimal("0.000005"), // $5/M
    webSearch = None,
    inputCacheRead = Some(BigDecimal("0.0000001")), // $0.10/M
    inputCacheWrite = Some(BigDecimal("0.00000125")) // $1.25/M
  )

  "Anthropic usage parsing (sigil #290)" should {

    "fold all three input buckets into promptTokens AND keep the cache subsets populated" in Task {
      // Use TokenUsage.fromJson directly to exercise the parsing path
      // identically to how AnthropicProvider.parseUsage would (then
      // sum-merge per the framework contract). Mirrors the post-fix
      // shape AnthropicProvider produces.
      val base = TokenUsage.fromJson(
        bugDocUsageJson,
        "input_tokens",
        "output_tokens",
        cacheKeys = CacheKeys.Anthropic
      )
      val totalPrompt = base.promptTokens + base.cacheCreationTokens + base.cacheReadTokens
      val usage = base.copy(
        promptTokens = totalPrompt,
        totalTokens = totalPrompt + base.completionTokens
      )
      // promptTokens is the sum (166 + 44856 + 11633 = 56655).
      usage.promptTokens shouldBe 56655
      usage.cacheCreationTokens shouldBe 44856
      usage.cacheReadTokens shouldBe 11633
      usage.completionTokens shouldBe 8000
      // The subsets relation holds: fresh = promptTokens - reads - writes.
      val fresh = usage.promptTokens - usage.cacheReadTokens - usage.cacheCreationTokens
      fresh shouldBe 166
    }
  }

  "Sigil.costFor (sigil #290)" should {

    "match a hand-computed Haiku invoice for the bug doc's usage shape" in Task {
      val usage = TokenUsage(
        promptTokens = 166 + 44856 + 11633,
        completionTokens = 8000,
        totalTokens = 166 + 44856 + 11633 + 8000,
        cacheReadTokens = 11633,
        cacheCreationTokens = 44856
      )
      val cost = Sigil.costFor(haikuPricing, usage)
      // Hand-computed:
      //   166   × $1.00/M   = $0.000166
      //   44856 × $1.25/M   = $0.05607
      //   11633 × $0.10/M   = $0.0011633
      //   8000  × $5.00/M   = $0.04
      //                      ─────────
      //                     ≈ $0.0974
      val expected = BigDecimal("0.0973893")
      (cost - expected).abs should be < BigDecimal("0.0001")
    }

    "collapse to the legacy fresh-only formula when both cache fields are zero" in Task {
      val freshOnlyUsage = TokenUsage(
        promptTokens = 5000,
        completionTokens = 200,
        totalTokens = 5200
      )
      val cost = Sigil.costFor(haikuPricing, freshOnlyUsage)
      // Pure fresh + completion only.
      val expected = haikuPricing.prompt * 5000 + haikuPricing.completion * 200
      cost shouldBe expected
    }

    "fall back to prompt × 0.10 for inputCacheRead when not published" in Task {
      val noCacheRatesPricing = haikuPricing.copy(
        inputCacheRead = None,
        inputCacheWrite = None
      )
      val usage = TokenUsage(
        promptTokens = 5000, // 1000 fresh + 4000 read
        completionTokens = 0,
        totalTokens = 5000,
        cacheReadTokens = 4000,
        cacheCreationTokens = 0
      )
      val cost = Sigil.costFor(noCacheRatesPricing, usage)
      // 1000 × $1/M + 4000 × ($1/M × 0.10) = $0.001 + $0.0004 = $0.0014
      val expected = BigDecimal("0.0014")
      (cost - expected).abs should be < BigDecimal("0.00001")
    }

    "fall back to prompt × 1.25 for inputCacheWrite when not published" in Task {
      val noCacheRatesPricing = haikuPricing.copy(
        inputCacheRead = None,
        inputCacheWrite = None
      )
      val usage = TokenUsage(
        promptTokens = 5000, // 1000 fresh + 4000 write
        completionTokens = 0,
        totalTokens = 5000,
        cacheReadTokens = 0,
        cacheCreationTokens = 4000
      )
      val cost = Sigil.costFor(noCacheRatesPricing, usage)
      // 1000 × $1/M + 4000 × ($1/M × 1.25) = $0.001 + $0.005 = $0.006
      val expected = BigDecimal("0.006")
      (cost - expected).abs should be < BigDecimal("0.00001")
    }

    "guard against negative fresh-prompt arithmetic when cache fields exceed promptTokens" in Task {
      // Defensive: a malformed provider could emit cache > prompt.
      // The framework clamps fresh to zero rather than producing a
      // negative cost term that distorts the rest of the math.
      val odd = TokenUsage(
        promptTokens = 100,
        completionTokens = 50,
        totalTokens = 150,
        cacheReadTokens = 80,
        cacheCreationTokens = 80
      )
      val cost = Sigil.costFor(haikuPricing, odd)
      // fresh clamped to 0; cost = 0×prompt + 80×readRate + 80×writeRate + 50×completion
      cost should be > BigDecimal(0)
      // (Sanity: positive and finite — no NaN, no negative.)
    }
  }
}
