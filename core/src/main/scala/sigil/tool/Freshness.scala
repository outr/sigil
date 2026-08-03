package sigil.tool

/**
 * How a read-only tool's result relates to time. Drives the
 * orchestrator's turn-scoped read cache:
 *
 *   - [[Pure]] — same input always produces the same output; cache
 *     freely within the turn.
 *   - [[Stable]] — world-coupled; cacheable until ANY mutating call
 *     lands in the turn.
 *   - [[Volatile]] — advancing external state (a process's output
 *     stream, the current turn's context breakdown); never cached.
 *     Result frames of Volatile reads are also elided from later
 *     turns' prompts by the standard curator — the result is
 *     re-derivable and stale copies only accumulate.
 */
enum Freshness {
  case Pure
  case Stable
  case Volatile
}
