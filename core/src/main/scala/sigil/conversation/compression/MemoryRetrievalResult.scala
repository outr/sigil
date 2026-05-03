package sigil.conversation.compression

import lightdb.id.Id
import sigil.conversation.ContextMemory

/**
 * Output of a [[MemoryRetriever]] pass. The two buckets map directly
 * onto [[sigil.conversation.TurnInput.memories]] and
 * [[sigil.conversation.TurnInput.criticalMemories]] — the curator
 * copies each vector straight through.
 *
 *   - [[memories]]: retrieval-scored ids, typically top-K semantically
 *     similar to the current turn's query. May be empty (no query, no
 *     matches).
 *   - [[criticalMemories]]: always-on ids — pinned
 *     ([[sigil.conversation.ContextMemory.pinned]] = `true`) records
 *     in the spaces the chain can access. Render in a distinct
 *     "Pinned directives" section in the provider system prompt. The
 *     field name retains "critical" for wire / persistence
 *     compatibility with [[sigil.conversation.TurnInput]].
 *
 * Implementations MUST ensure the two vectors don't duplicate — an id
 * in `criticalMemories` should not also appear in `memories`, since
 * the provider renders each twice otherwise.
 */
case class MemoryRetrievalResult(memories: Vector[Id[ContextMemory]],
                                 criticalMemories: Vector[Id[ContextMemory]])

object MemoryRetrievalResult {
  val empty: MemoryRetrievalResult = MemoryRetrievalResult(Vector.empty, Vector.empty)
}
