package sigil.conversation.compression.retrieval

import sigil.conversation.ContextMemory

/**
 * The value threaded through the retrieval pipeline's stages.
 *
 *   - `lexical` / `vectorHits` — the question legs' candidate records
 *     (BM25 and embedding similarity over the user's question), in
 *     each leg's relevance order. Filled by the Recall stage, narrowed
 *     by the Gate stage, consumed by the Fuse stage.
 *   - `keywordHits` — the context leg's candidates: BM25 over the
 *     conversation's context terms (classifier keywords + topic
 *     label), kept separate so conversational theme contributes to
 *     the fusion without diluting the question legs.
 *   - `ranked` — the fused (then optionally reranked, then budgeted)
 *     result list, best first. The pipeline's output.
 */
case class MemoryRetrievalState(lexical: Vector[ContextMemory] = Vector.empty,
                                vectorHits: Vector[ContextMemory] = Vector.empty,
                                keywordHits: Vector[ContextMemory] = Vector.empty,
                                ranked: Vector[ContextMemory] = Vector.empty)
