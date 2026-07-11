package sigil.conversation.compression

import lightdb.id.Id
import sigil.event.{Event, Message, MessageRole, ToolInvoke}
import sigil.tool.ToolName

/**
 * Decides whether (and what) to fold at intra-turn boundaries within
 * a single agent loop. A long agent loop (32+ tool-call iterations
 * against one user prompt) accumulates the full history on every
 * iteration's prompt; compression at user-turn boundaries only doesn't
 * help, because the wire-cost is paid by iteration 32 against the
 * content of iterations 1-31 within the same turn.
 *
 * The framework consults this trait between iterations in
 * [[sigil.Sigil.runAgentLoop]]:
 *
 *   1. [[shouldCompact]] — fire OR skip this iteration's boundary.
 *      Cheap predicate evaluated unconditionally; default impl mixes
 *      size-pressure + natural-boundary triggers.
 *   2. [[selectFoldable]] — when firing, return the event ids whose
 *      frames are safe to subsume into a single summary. Empty list
 *      = no actual compaction this iteration (predicate fired but
 *      nothing safe to fold).
 *
 * When [[selectFoldable]] returns a non-empty list, the framework
 * invokes [[MemoryContextCompressor.compressCovering]] to summarize
 * those events and persists the resulting [[ContextSummary]] with
 * its `coversEventIds` set. The curator filters those events from
 * subsequent turns' frames — the agent sees the summary's text in
 * their place. Durable event log is untouched: anything compacted
 * remains recoverable via `search_conversation` /
 * `recall_memory` / `lookup`.
 *
 * Apps override via [[sigil.Sigil.intraTurnCompactor]] for app-
 * specific triggers (e.g. "fold after every `preview_theme` success"
 * or "treat tool X as a sub-task-closed signal").
 */
trait IntraTurnCompactor {

  /**
   * Decide whether to fire compression at this iteration boundary.
   *
   * @param turnEvents events accumulated since this user turn began
   *                   (events after the most recent user-author Message)
   * @param estimatedTokens heuristic estimate of the wire-token cost
   *                        of those events, courtesy of the framework
   *                        (so apps don't re-tokenize)
   * @param threshold per-iteration cost threshold above which the
   *                  framework considers folding worthwhile; derived
   *                  from the model's `contextLength` and
   *                  `inputTokensPerMinute` via
   *                  [[sigil.Sigil.compressionTriggerTokens]]
   */
  def shouldCompact(turnEvents: Vector[Event], estimatedTokens: Long, threshold: Long): Boolean

  /**
   * Pick a foldable subset of `turnEvents` — the framework will
   * summarize their frames into one [[ContextSummary]] that subsumes
   * the same ground in the next iteration's prompt. Return an empty
   * list to skip compaction even when [[shouldCompact]] fired.
   *
   * Implementations should consult their [[CompactionInvariant]] set
   * against `ctx` and drop only events not in the union of protected
   * ids — never fold across an invariant.
   */
  def selectFoldable(turnEvents: Vector[Event], ctx: TurnEventsContext): List[Id[Event]]
}

/**
 * Default [[IntraTurnCompactor]] — fires on size pressure OR after
 * natural sub-task boundaries:
 *
 *   - **Size pressure**: `estimatedTokens >= threshold` (caller
 *     supplies the threshold; framework default ~60% of the tighter
 *     of `contextLength` and `inputTokensPerMinute × safetyMargin`).
 *   - **Natural boundary — `respond`**: most recent event is a
 *     standard-role agent [[Message]] with non-empty content. The
 *     agent just synthesized a user-visible reply; the tool calls
 *     and reads that fed it are now compressible into "we did X."
 *   - **Natural boundary — terminal tool**: most recent event is a
 *     [[ToolInvoke]] whose `toolName` is in [[terminalTools]]
 *     (default empty; apps populate). Use case: `preview_theme`,
 *     `create_page`, `commit` — anything whose success marks a
 *     sub-task closed.
 *
 * Selection rule: filter the slice by the union of every
 * [[CompactionInvariant]] in [[invariants]] (plus a built-in
 * [[CompactionInvariant.RecentTail]] sized at [[recentTailN]]).
 * Everything outside the protected union is foldable.
 */
case class StandardIntraTurnCompactor(terminalTools: Set[ToolName] = Set.empty,
                                      recentTailN: Int = 4,
                                      invariants: List[CompactionInvariant] = CompactionInvariant.standard)
  extends IntraTurnCompactor {

  private val tailInvariant = CompactionInvariant.RecentTail(recentTailN)
  private val effectiveInvariants = invariants :+ tailInvariant

  override def shouldCompact(turnEvents: Vector[Event], estimatedTokens: Long, threshold: Long): Boolean =
    if (turnEvents.size <= recentTailN) false
    else if (estimatedTokens >= threshold) true
    else turnEvents.lastOption match {
      case Some(m: Message) if m.role == MessageRole.Standard && m.content.nonEmpty => true
      case Some(ti: ToolInvoke) if terminalTools.contains(ti.toolName) => true
      case _ => false
    }

  override def selectFoldable(turnEvents: Vector[Event], ctx: TurnEventsContext): List[Id[Event]] =
    if (turnEvents.size <= recentTailN) Nil
    else {
      val protectedIds: Set[Id[Event]] =
        effectiveInvariants.iterator.flatMap(_.applicableIds(turnEvents, ctx)).toSet
      turnEvents.iterator
        .filterNot(e => protectedIds.contains(e._id))
        .map(_._id)
        .toList
    }
}
