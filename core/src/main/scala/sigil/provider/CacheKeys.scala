package sigil.provider

/**
 * Describes where a provider's `usage` JSON object carries
 * prompt-caching token counts, so [[TokenUsage.fromJson]] can read
 * them without each provider duplicating the extraction logic.
 *
 * Providers vary in both naming and shape:
 *   - Anthropic and DeepSeek place cache counts as sibling integer
 *     keys on the usage object ([[Flat]]).
 *   - OpenAI nests a single `cached_tokens` integer inside a details
 *     sub-object ([[Nested]]).
 *   - Google reports a single sibling cache-read integer on the usage
 *     object and no cache-creation count ([[FlatRead]]).
 */
enum CacheKeys {

  /**
   * No cache accounting on the wire; both cache fields stay `0`.
   */
  case None

  /**
   * Two sibling integer keys on the usage object: tokens served
   * from cache and tokens written to cache, respectively.
   */
  case Flat(readKey: String, creationKey: String)

  /**
   * A single sibling integer key on the usage object reporting cache
   * reads only; cache-creation stays `0`. Gemini's generation
   * response carries `cachedContentTokenCount` this way — the
   * cache-create call is a separate request, so no creation count
   * rides the generation usage block.
   */
  case FlatRead(readKey: String)

  /**
   * A `cachedKey` integer nested inside a `detailsKey` sub-object,
   * reporting cache reads only.
   */
  case Nested(detailsKey: String, cachedKey: String)
}

object CacheKeys {

  /**
   * Anthropic Messages API: `cache_read_input_tokens` /
   * `cache_creation_input_tokens` siblings on `usage`.
   */
  val Anthropic: CacheKeys =
    CacheKeys.Flat("cache_read_input_tokens", "cache_creation_input_tokens")

  /**
   * DeepSeek chat-completions: `prompt_cache_hit_tokens` /
   * `prompt_cache_miss_tokens` siblings on `usage`. The miss count is
   * the freshly-cached portion, so it maps to `cacheCreationTokens`.
   */
  val DeepSeek: CacheKeys =
    CacheKeys.Flat("prompt_cache_hit_tokens", "prompt_cache_miss_tokens")

  /**
   * OpenAI chat-completions: `prompt_tokens_details.cached_tokens`.
   */
  val OpenAIChat: CacheKeys =
    CacheKeys.Nested("prompt_tokens_details", "cached_tokens")

  /**
   * OpenAI Responses API: `input_tokens_details.cached_tokens`.
   */
  val OpenAIResponses: CacheKeys =
    CacheKeys.Nested("input_tokens_details", "cached_tokens")

  /**
   * Google Gemini: `cachedContentTokenCount` sibling on
   * `usageMetadata`, reporting the prompt tokens served from a
   * `cachedContents` resource.
   */
  val Google: CacheKeys =
    CacheKeys.FlatRead("cachedContentTokenCount")
}
