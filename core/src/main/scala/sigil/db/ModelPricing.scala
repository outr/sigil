package sigil.db

import fabric.rw.*

/**
 * Per-token / per-call pricing for a model, all values in USD.
 *
 * @param prompt          Cost per input (prompt) token.
 * @param completion      Cost per output (completion) token.
 * @param webSearch       Cost per web-search tool invocation, when the model supports it.
 * @param inputCacheRead  Cost per prompt-cache read token (discounted reuse of
 *                        cached prompt prefix).
 * @param inputCacheWrite Cost per prompt-cache write token (premium charged on
 *                        the FIRST call that warms a cache prefix; subsequent
 *                        reads of the same prefix use [[inputCacheRead]]).
 *                        Sigil #290 — OpenRouter publishes
 *                        `input_cache_write` per-model for providers that
 *                        bill cache writes separately (Anthropic Haiku /
 *                        Sonnet / Opus all do). `None` means the framework
 *                        falls back to a Anthropic-documented multiplier
 *                        (`prompt × 1.25`) when costing a cache-creation
 *                        token; consumers wanting exact billing supply the
 *                        published value.
 */
case class ModelPricing(prompt: BigDecimal,
                        completion: BigDecimal,
                        webSearch: Option[BigDecimal],
                        inputCacheRead: Option[BigDecimal],
                        inputCacheWrite: Option[BigDecimal] = None)
  derives RW
