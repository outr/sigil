package sigil.conversation.compression.retrieval

import sigil.conversation.ContextMemory

/**
 * The value threaded through the retrieval pipeline's stages.
 *
 *   - `lexical` / `vectorHits` — the two recall legs' candidate
 *     records, in each leg's relevance order. Filled by the Recall
 *     stage, narrowed by the Gate stage, consumed by the Fuse stage.
 *   - `ranked` — the fused (then optionally reranked, then budgeted)
 *     result list, best first. The pipeline's output.
 */
case class MemoryRetrievalState(lexical: Vector[ContextMemory] = Vector.empty,
                                vectorHits: Vector[ContextMemory] = Vector.empty,
                                ranked: Vector[ContextMemory] = Vector.empty)
