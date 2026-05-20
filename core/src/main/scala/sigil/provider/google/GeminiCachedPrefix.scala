package sigil.provider.google

import lightdb.time.Timestamp

/**
 * A live Gemini `cachedContents` resource tracked by
 * [[GeminiContextCache]].
 *
 * @param resourceName the server-assigned resource name (e.g.
 *                     `cachedContents/abc123`) referenced verbatim on
 *                     a subsequent `generateContent` request's
 *                     `cachedContent` field
 * @param expiresAt    client-side view of when the resource lapses,
 *                     computed from the TTL passed to
 *                     `cachedContents.create`
 */
case class GeminiCachedPrefix(resourceName: String, expiresAt: Timestamp) {

  /**
   * Whether the resource is still safe to reference at `now`,
   * accounting for [[GeminiContextCache.expiryMargin]] so an
   * in-flight request never races the server-side eviction.
   */
  def isLive(now: Timestamp): Boolean =
    expiresAt.value - GeminiContextCache.expiryMargin.toMillis > now.value
}
