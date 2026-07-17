package sigil.provider.anthropic

/**
 * Anthropic-specific constants. Model metadata lives in
 * [[sigil.cache.ModelRegistry]] — populated by
 * [[sigil.controller.OpenRouter.refreshModels]] — and is read fresh on
 * each access via `Provider.models`.
 */
object Anthropic {
  val Provider: String = "anthropic"
  val ApiVersion: String = "2023-06-01"

  /**
   * Beta header that unlocks the 1-hour `cache_control` TTL on top of
   * the default 5-minute ephemeral cache. Sent only when prompt
   * caching is engaged.
   */
  val ExtendedCacheTtlBeta: String = "extended-cache-ttl-2025-04-11"

  /**
   * Sigil #284 — response header carrying the per-minute input-token
   * ceiling Anthropic enforces for the calling account / model pair.
   * Present on every 200 response and on 429 rate-limit replies.
   * Sniffed by [[AnthropicProvider]] to auto-populate
   * `Model.inputTokensPerMinute`, which engages #283's pre-flight
   * rate-limit guard on subsequent calls.
   */
  val RateLimitInputTokensHeader: String = "anthropic-ratelimit-input-tokens-limit"

  def stripProviderPrefix(sigilModelId: String): String = {
    val prefix = s"$Provider/"
    if (sigilModelId.startsWith(prefix)) sigilModelId.drop(prefix.length) else sigilModelId
  }

  /**
   * Whether a model (post `stripProviderPrefix`) supports prompt
   * caching. Every Claude model does; the gate keeps `cache_control`
   * breakpoints off any non-Claude vendor model that happens to be
   * routed through the Anthropic Messages wire.
   */
  def supportsPromptCaching(strippedModelName: String): Boolean =
    strippedModelName.toLowerCase.contains("claude")
}
