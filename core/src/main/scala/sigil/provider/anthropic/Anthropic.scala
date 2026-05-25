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

  /** Beta header that unlocks the 1-hour `cache_control` TTL on top of
    * the default 5-minute ephemeral cache. Sent only when prompt
    * caching is engaged. */
  val ExtendedCacheTtlBeta: String = "extended-cache-ttl-2025-04-11"

  def stripProviderPrefix(sigilModelId: String): String = {
    val prefix = s"$Provider/"
    if (sigilModelId.startsWith(prefix)) sigilModelId.drop(prefix.length) else sigilModelId
  }

  /** Whether a model (post `stripProviderPrefix`) supports prompt
    * caching. Every Claude model does; the gate keeps `cache_control`
    * breakpoints off any non-Claude vendor model that happens to be
    * routed through the Anthropic Messages wire. */
  def supportsPromptCaching(strippedModelName: String): Boolean =
    strippedModelName.toLowerCase.contains("claude")

  /** Sigil #276 — published per-model `max_tokens` ceilings for the
    * current Anthropic catalog. Used as a fallback when the
    * [[sigil.cache.ModelRegistry]]'s `topProvider.maxCompletionTokens`
    * is empty for a model id (typical for Anthropic-direct apps that
    * don't run an OpenRouter catalog refresh). Keys match
    * `stripProviderPrefix(modelId.value)`.
    *
    * Keep in sync with Anthropic's public docs. A miss falls through
    * to [[SafeFallbackMaxTokens]] — never a wire reject. */
  val KnownMaxCompletionTokens: Map[String, Int] = Map(
    "claude-haiku-4-5-20251001" -> 64000,
    "claude-sonnet-4-6"         -> 64000,
    "claude-opus-4-7"           -> 32000
  )

  /** Last-resort `max_tokens` when neither the registry nor
    * [[KnownMaxCompletionTokens]] knows the model. Sigil #276 — chosen
    * to match the historical AnthropicProvider default rather than to
    * pick a meaningfully-correct value; apps relying on this fallback
    * for a real production model should register the model in the
    * cache or extend the known-ceilings map. */
  val SafeFallbackMaxTokens: Int = 4096
}
