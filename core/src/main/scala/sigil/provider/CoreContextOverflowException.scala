package sigil.provider

import lightdb.id.Id
import sigil.db.Model
import sigil.signal.CriticalMemoryShare

/**
 * Thrown by `Sigil.persistMemory` / `upsertMemoryByKey` when adding /
 * replacing a [[sigil.conversation.MemorySource.Critical]] memory
 * would push the conversation's inviolable "core context" (Critical
 * memories + system-prompt overhead) beyond
 * [[sigil.Sigil.coreContextShareLimit]] (default 50%) of the model's
 * window.
 *
 * The cap exists so the framework's auto-shedding machinery always
 * has room to manoeuvre — the sheddable budget (frames, retrieved
 * memories, Information catalog, tool roster) is guaranteed at least
 * `1 - coreContextShareLimit` of the window. Apps catch and either
 * trim the memory's summary / fact and retry, persist as a non-
 * critical source, or raise the limit if their app's invariant
 * tolerates a larger inviolable share.
 */
final class CoreContextOverflowException(val wouldBeTotal: Int,
                                         val limit: Int,
                                         val modelId: Id[Model],
                                         val largestExistingContributors: List[CriticalMemoryShare])
  extends RuntimeException({
    val top = largestExistingContributors.take(3).map(s => s"${s.key} @${s.tokens} tok").mkString(", ")
    s"Persisting this Critical memory would push core-context to $wouldBeTotal tokens, exceeding the cap of $limit " +
      s"(model ${modelId.value}). Largest existing pinned: $top. Trim the memory's summary, persist as a sheddable " +
      s"source, or raise Sigil.coreContextShareLimit if a larger inviolable share is intentional."
  })
