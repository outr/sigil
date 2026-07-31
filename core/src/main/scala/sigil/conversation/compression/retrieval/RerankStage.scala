package sigil.conversation.compression.retrieval

import rapid.Task

/**
 * Rerank — optional reorder of the fused list before the Budget cut.
 * Default OFF (`reranker = None` — the stage is an identity pass);
 * apps opt in with [[LLMMemoryReranker]] over a configured model, or
 * any custom [[MemoryReranker]]. A reranker failure falls back to the
 * fused order — retrieval never fails on a rerank hiccup.
 */
case class RerankStage(reranker: Option[MemoryReranker] = None) extends MemoryRetrievalStage {
  override val name: String = "rerank"

  override def run(state: MemoryRetrievalState, ctx: MemoryRetrievalContext): Task[MemoryRetrievalState] =
    reranker match {
      case None => Task.pure(state)
      case Some(_) if state.ranked.size <= 1 => Task.pure(state)
      case Some(r) =>
        r.rerank(ctx.sigil, ctx.query, state.ranked)
          .map(ordered => state.copy(ranked = ordered))
          .handleError { e =>
            Task {
              scribe.warn(s"RerankStage: reranker failed (${e.getMessage}) — keeping fused order")
              state
            }
          }
    }
}
