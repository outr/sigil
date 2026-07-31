package sigil.conversation.compression.retrieval

import rapid.Task
import sigil.Sigil
import sigil.conversation.ContextMemory

/**
 * Reorders a fused candidate list by relevance to the turn's query.
 * The contract is order-only: implementations return the same records
 * (dropping none, adding none) in a new order; the Budget stage cuts
 * afterwards.
 *
 * [[LLMMemoryReranker]] adapts the existing
 * [[sigil.vector.LLMReranker]]; specs stub the trait directly.
 */
trait MemoryReranker {
  def rerank(sigil: Sigil, query: String, memories: Vector[ContextMemory]): Task[Vector[ContextMemory]]
}
