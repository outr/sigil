package sigil.tool.provider

import fabric.rw.*

/**
 * Result of a [[ListModelsTool]] call.
 *
 *   - `total` — number of models matching the filter (before `limit`).
 *   - `returned` — number actually populated in `models` (`<= total`).
 *   - `models` — the records, sorted by id ascending for deterministic
 *     paging.
 */
case class ListModelsOutput(total: Int,
                            returned: Int,
                            models: List[ModelSummary]) derives RW
