package sigil.conversation.compression

import lightdb.id.Id
import sigil.event.{Event, Message, MessageRole, ToolInvoke}
import sigil.tool.ToolName

/**
 * Sigil #285 — decides whether (and what) to fold at intra-turn
 * boundaries within a single agent loop. A long agent loop (32+
 * tool-call iterations against one user prompt) accumulates the full
 * history on every iteration's prompt; compression at user-turn
 * boundaries only doesn't help, because the wire-cost is paid by
 * iteration 32 against the content of iterations 1-31 within the
 * same turn.
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
  /** Decide whether to fire compression at this iteration boundary.
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
    *                  [[sigil.Sigil.compressionTriggerTokens]] */
  def shouldCompact(turnEvents: Vector[Event], estimatedTokens: Long, threshold: Long): Boolean

  /** Pick a foldable prefix of `turnEvents` — the framework will
    * summarize their frames into one [[ContextSummary]] that subsumes
    * the same ground in the next iteration's prompt. Return an empty
    * list to skip compaction even when [[shouldCompact]] fired. */
  def selectFoldable(turnEvents: Vector[Event]): List[Id[Event]]
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
 * Selection rule: fold every event except the last `keepRecent`
 * (default 4 — typically the last respond/tool-call/tool-result
 * triplet plus the boundary event the trigger fired on). Apps with
 * stricter "never fold this kind of event" rules subclass and
 * override [[selectFoldable]].
 *
 * NEVER folded by construction:
 *   - The original user message of this turn (excluded by the
 *     framework's "events since user turn began" slice — it's the
 *     turn's predecessor, not in `turnEvents`).
 *   - The latest `keepRecent` events in the turn (controlled here).
 */
case class StandardIntraTurnCompactor(terminalTools: Set[ToolName] = Set.empty,
                                       keepRecent: Int = 4) extends IntraTurnCompactor {

  override def shouldCompact(turnEvents: Vector[Event], estimatedTokens: Long, threshold: Long): Boolean = {
    if (turnEvents.size <= keepRecent) false
    else if (estimatedTokens >= threshold) true
    else turnEvents.lastOption match {
      case Some(m: Message) if m.role == MessageRole.Standard && m.content.nonEmpty => true
      case Some(ti: ToolInvoke) if terminalTools.contains(ti.toolName)               => true
      case _                                                                          => false
    }
  }

  override def selectFoldable(turnEvents: Vector[Event]): List[Id[Event]] = {
    if (turnEvents.size <= keepRecent) Nil
    else turnEvents.dropRight(keepRecent).iterator.map(_._id).toList
  }
}
