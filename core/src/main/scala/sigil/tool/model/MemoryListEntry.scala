package sigil.tool.model

import fabric.rw.*
import sigil.conversation.ContextMemory
import sigil.tokenize.HeuristicTokenizer

/**
 * One memory row in a [[ListMemoriesOutput.Listed]] page.
 *
 *   - `key` — the memory's stable key (its `_id` value when no key
 *     was assigned).
 *   - `label` / `summary` — the human-facing label and short summary.
 *   - `tokens` — heuristic token cost of the rendered text.
 *   - `spaceId` — the memory's space discriminator value.
 *   - `pinned` — `true` when the memory renders every turn.
 *   - `confidence` — the writer's confidence the fact is correct.
 *   - `justification` — free-form reason the memory was recorded.
 */
case class MemoryListEntry(key: String,
                           label: String,
                           summary: String,
                           tokens: Int,
                           spaceId: String,
                           pinned: Boolean,
                           confidence: Double,
                           justification: Option[String])
  derives RW

object MemoryListEntry {

  /**
   * Sigil #292 — build a [[MemoryListEntry]] from a
   * [[ContextMemory]]. Used by both [[sigil.tool.context.ListMemoriesTool]]'s
   * agent-facing list path AND the framework's
   * [[sigil.signal.RequestMemoryList]] UI-snapshot dispatcher so the
   * two stay shape-identical.
   */
  def from(m: ContextMemory): MemoryListEntry = {
    val rendered = if (m.summary.trim.nonEmpty) m.summary else m.fact
    MemoryListEntry(
      key = m.key.getOrElse(m._id.value),
      label = m.label,
      summary = m.summary,
      tokens = HeuristicTokenizer.count(rendered),
      spaceId = m.spaceId.value,
      pinned = m.pinned,
      confidence = m.confidence,
      justification = m.justification
    )
  }
}
