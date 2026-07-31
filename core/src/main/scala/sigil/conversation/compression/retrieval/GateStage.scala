package sigil.conversation.compression.retrieval

import rapid.Task
import sigil.conversation.ContextMemory

/**
 * Gate — the one place the shared recall predicate applies to both
 * legs' candidates:
 *
 *   - [[ContextMemory.isRecallable]] — current version (`validUntil`
 *     unset), `Approved`, unexpired;
 *   - unpinned — pinned memories render in the Pinned section already;
 *   - mode affinity — a memory with non-empty `modeAffinity` only
 *     surfaces when the conversation's current mode is in the set.
 */
case class GateStage() extends MemoryRetrievalStage {
  override val name: String = "gate"

  override def run(state: MemoryRetrievalState, ctx: MemoryRetrievalContext): Task[MemoryRetrievalState] = Task {
    def passes(m: ContextMemory): Boolean =
      !m.pinned &&
        m.isRecallable(ctx.now) &&
        (m.modeAffinity.isEmpty || ctx.currentMode.exists(m.modeAffinity.contains))
    state.copy(lexical = state.lexical.filter(passes), vectorHits = state.vectorHits.filter(passes))
  }
}
