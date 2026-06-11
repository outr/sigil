package sigil.provider

import fabric.Json
import fabric.rw.*

/**
 * Per-call token usage counts. `isEstimated = true` marks a synthetic
 * mid-stream estimate emitted by the wire decoder so consumer UIs can
 * render live token tickers during long reasoning / content streams;
 * the final authoritative emission from the provider's `usage` chunk
 * carries `isEstimated = false`. Default is `false` so non-streaming
 * callers stay back-compat.
 *
 * `cacheReadTokens` and `cacheCreationTokens` carry prompt-caching
 * accounting. `cacheReadTokens` is the count of prompt tokens served
 * from a cache hit (billed at a steep discount); `cacheCreationTokens`
 * is the count written into the cache on this call (billed at a small
 * premium over base input tokens). Both default to `0` — providers
 * without cache accounting, or calls with no cache activity, leave
 * them unset. The two are subsets of `promptTokens`: a cached-prefix
 * call reports the same `promptTokens` it always would, with the
 * cache-served portion additionally broken out here.
 */
case class TokenUsage(promptTokens: Int,
                      completionTokens: Int,
                      totalTokens: Int,
                      isEstimated: Boolean = false,
                      cacheReadTokens: Int = 0,
                      cacheCreationTokens: Int = 0) derives RW {

  /** Sigil #381 — accumulate two authoritative per-call usage records.
    * A turn makes one provider call per loop iteration; each call's
    * usage sums onto the event it lands on instead of clobbering the
    * last. Every field (cache subsets included) adds; the result is
    * authoritative (`isEstimated = false`). */
  def +(other: TokenUsage): TokenUsage = TokenUsage(
    promptTokens        = promptTokens + other.promptTokens,
    completionTokens    = completionTokens + other.completionTokens,
    totalTokens         = totalTokens + other.totalTokens,
    isEstimated         = false,
    cacheReadTokens     = cacheReadTokens + other.cacheReadTokens,
    cacheCreationTokens = cacheCreationTokens + other.cacheCreationTokens
  )
}

object TokenUsage {

  /** The additive identity — a zero-usage record. */
  val zero: TokenUsage = TokenUsage(0, 0, 0)

  /** Build a [[TokenUsage]] from a provider's `usage` JSON object.
    *
    * Each provider names the three int fields differently
    * (`prompt_tokens` vs `input_tokens` vs `promptTokenCount`, etc.);
    * the caller supplies the key names. When `totalKey` is `None` the
    * total is computed as `prompt + completion` — some providers
    * (notably Anthropic) don't carry a total field on the wire.
    * Missing fields read as `0`.
    *
    * Cache accounting is read from one of three layouts the caller
    * selects via [[CacheKeys]]:
    *   - [[CacheKeys.Flat]] — two sibling keys on the usage object
    *     (Anthropic's `cache_read_input_tokens` /
    *     `cache_creation_input_tokens`, DeepSeek's
    *     `prompt_cache_hit_tokens` / `prompt_cache_miss_tokens`).
    *   - [[CacheKeys.FlatRead]] — one sibling cache-read key on the
    *     usage object (Google's `cachedContentTokenCount`);
    *     cache-creation stays `0`.
    *   - [[CacheKeys.Nested]] — a `cached_tokens` int inside a
    *     details object (OpenAI's `prompt_tokens_details` on chat
    *     completions, `input_tokens_details` on the Responses wire).
    *     The Nested layout reports cache reads only; cache-creation
    *     stays `0`.
    *
    * Pass [[CacheKeys.None]] for providers with no cache accounting to
    * leave both cache fields `0`. */
  def fromJson(json: Json,
               promptKey: String,
               completionKey: String,
               totalKey: Option[String] = None,
               cacheKeys: CacheKeys = CacheKeys.None): TokenUsage = {
    val prompt = json.get(promptKey).map(_.asInt).getOrElse(0)
    val completion = json.get(completionKey).map(_.asInt).getOrElse(0)
    val total = totalKey match {
      case Some(key) => json.get(key).map(_.asInt).getOrElse(0)
      case None      => prompt + completion
    }
    val (cacheRead, cacheCreation) = cacheKeys match {
      case CacheKeys.None =>
        (0, 0)
      case CacheKeys.Flat(readKey, creationKey) =>
        val read = json.get(readKey).map(_.asInt).getOrElse(0)
        val creation = json.get(creationKey).map(_.asInt).getOrElse(0)
        (read, creation)
      case CacheKeys.FlatRead(readKey) =>
        val read = json.get(readKey).map(_.asInt).getOrElse(0)
        (read, 0)
      case CacheKeys.Nested(detailsKey, cachedKey) =>
        val read = json.get(detailsKey).flatMap(_.get(cachedKey)).map(_.asInt).getOrElse(0)
        (read, 0)
    }
    TokenUsage(
      promptTokens = prompt,
      completionTokens = completion,
      totalTokens = total,
      cacheReadTokens = cacheRead,
      cacheCreationTokens = cacheCreation
    )
  }
}
