package sigil.provider.google

/**
 * Typed wrapper over the SHA-256 hex digest of a request's stable
 * prefix (system instruction + tool-schema block). Used as the key
 * into [[GeminiContextCache]] so two requests whose cacheable prefix
 * is byte-identical resolve to the same `cachedContents` resource.
 */
case class GeminiCacheKey(hex: String) extends AnyVal
