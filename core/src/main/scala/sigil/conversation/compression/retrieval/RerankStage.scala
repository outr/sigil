package sigil.conversation.compression.retrieval

import rapid.Task

/**
 * Rerank — optional REORDER of the fused list before the Budget cut.
 * Default OFF (`reranker = None` — the stage is an identity pass);
 * apps opt in with [[LLMMemoryReranker]] over a configured model, or
 * any custom [[MemoryReranker]]. A reranker failure falls back to the
 * fused order — retrieval never fails on a rerank hiccup.
 *
 * A reranker is contractually a permutation. One that drops, adds, or
 * substitutes records (a truncating LLM reranker, an id the model
 * hallucinated) is rejected wholesale with a warning rather than
 * partially applied — silently shrinking the candidate set here would
 * look like a retrieval miss several layers downstream.
 */
case class RerankStage(reranker: Option[MemoryReranker] = None) extends MemoryRetrievalStage {
  override val name: String = "rerank"

  override def run(state: MemoryRetrievalState, ctx: MemoryRetrievalContext): Task[MemoryRetrievalState] =
    reranker match {
      case None => Task.pure(state)
      case Some(_) if state.ranked.size <= 1 => Task.pure(state)
      case Some(r) =>
        r.rerank(ctx.sigil, ctx.query, state.ranked)
          .map { ordered =>
            if (ordered.map(_._id).toSet == state.ranked.map(_._id).toSet && ordered.size == state.ranked.size)
              state.copy(ranked = ordered)
            else {
              scribe.warn(s"RerankStage: reranker returned ${ordered.size} of ${state.ranked.size} records " +
                "(not a permutation of the fused set) — keeping fused order")
              state
            }
          }
          .handleError { e =>
            Task {
              scribe.warn(s"RerankStage: reranker failed (${e.getMessage}) — keeping fused order")
              state
            }
          }
    }
}
