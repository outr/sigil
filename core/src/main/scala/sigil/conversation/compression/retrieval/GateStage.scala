package sigil.conversation.compression.retrieval

import lightdb.id.Id
import rapid.Task
import sigil.conversation.ContextMemory
import sigil.provider.Mode

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
      !m.pinned && m.isRecallable(ctx.now) && GateStage.matchesMode(m, ctx.currentMode)
    state.copy(lexical = state.lexical.filter(passes), vectorHits = state.vectorHits.filter(passes))
  }
}

object GateStage {
  /** The per-memory mode-affinity gate. A memory with empty
    * `modeAffinity` is universal — surfaces regardless of mode. A
    * non-empty set means the memory only surfaces when `currentMode`
    * is in it. Shared with the pinned load, which applies the same
    * gate outside the pipeline. */
  def matchesMode(memory: ContextMemory, currentMode: Option[Id[Mode]]): Boolean =
    memory.modeAffinity.isEmpty || currentMode.exists(memory.modeAffinity.contains)
}
