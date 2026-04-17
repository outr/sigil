package sigil.db

import fabric.rw.*

/**
 * Per-token / per-call pricing for a model, all values in USD.
 *
 * @param prompt         Cost per input (prompt) token.
 * @param completion     Cost per output (completion) token.
 * @param webSearch      Cost per web-search tool invocation, when the model supports it.
 * @param inputCacheRead Cost per prompt-cache read token (discounted reuse of cached prompt prefix).
 */
case class ModelPricing(prompt: BigDecimal,
                        completion: BigDecimal,
                        webSearch: Option[BigDecimal],
                        inputCacheRead: Option[BigDecimal]) derives RW
