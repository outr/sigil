package sigil.provider

/**
 * Where a [[ContextSection]] lands in the rendered system prompt.
 *
 * The split is cache-aware: `StablePrefix` sections change rarely
 * across turns within one conversation, so providers with prompt
 * caching can serve them from a cache hit. `VolatileTail` sections
 * shift every turn and ride behind the request's cacheable prefix.
 */
enum Placement {
  case StablePrefix
  case VolatileTail
}
