package sigil.conversation.compression.retrieval

import rapid.Task

/**
 * Record — closes the retrieval feedback loop by batch-bumping
 * `accessCount` / `lastAccessedAt` on every SURFACED memory (the
 * post-budget ranked set) via
 * [[sigil.Sigil.recordMemoryAccesses]]. Fire-and-forget on a
 * background task, so retrieval latency is unaffected; and because
 * retrieval results are cached per conversation
 * ([[sigil.Sigil.cachedMemoryRetrieve]]), cache hits don't inflate
 * counts — only fresh computes mark access. The bumped fields feed
 * the Fuse stage's reinforcement term on later turns.
 */
case class RecordStage(enabled: Boolean = true) extends MemoryRetrievalStage {
  override val name: String = "record"

  override def run(state: MemoryRetrievalState, ctx: MemoryRetrievalContext): Task[MemoryRetrievalState] = Task {
    if (enabled && state.ranked.nonEmpty) {
      ctx.sigil.recordMemoryAccesses(state.ranked.map(_._id)).startUnit()
    }
    state
  }
}
