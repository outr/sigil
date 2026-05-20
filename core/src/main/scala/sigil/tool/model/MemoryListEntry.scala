package sigil.tool.model

import fabric.rw.*

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
                           justification: Option[String]) derives RW
