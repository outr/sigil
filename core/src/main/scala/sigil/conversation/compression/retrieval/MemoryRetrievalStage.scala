package sigil.conversation.compression.retrieval

import rapid.Task

/**
 * One named stage of the topical memory-retrieval pipeline. The
 * default [[sigil.conversation.compression.StandardMemoryRetriever]]
 * pipeline is:
 *
 * {{{
 * Recall (lexical ∥ vector, space-pushed-down)
 *   → Gate (recallable + mode affinity + unpinned)
 *   → Fuse (confidence-weighted RRF × recency × reinforcement)
 *   → Rerank (optional LLM reranker)
 *   → Budget (count cap + optional token cap)
 *   → Record (access marking)
 * }}}
 *
 * Stages transform a [[MemoryRetrievalState]] under a per-turn
 * [[MemoryRetrievalContext]] and compose as a plain `List` — the same
 * list-of-T contract the signal pipeline uses. Apps swap a stage by
 * supplying their own list to `StandardMemoryRetriever(pipeline = ...)`
 * (start from [[sigil.conversation.compression.StandardMemoryRetriever.defaultStages]]
 * and replace the entry) instead of reimplementing the whole retriever.
 */
trait MemoryRetrievalStage {
  def name: String

  def run(state: MemoryRetrievalState, ctx: MemoryRetrievalContext): Task[MemoryRetrievalState]
}
