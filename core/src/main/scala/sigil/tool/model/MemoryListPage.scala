package sigil.tool.model

import fabric.rw.*

/**
 * Pagination envelope for a [[ListMemoriesOutput.Listed]] page.
 *
 *   - `offset` — the 0-based offset this page started at.
 *   - `limit` — the (server-clamped) page size.
 *   - `returned` — how many records this page actually carries.
 *   - `totalMatched` — total records matching the filters across all
 *     pages.
 *   - `hasMore` — `true` when another page follows.
 */
case class MemoryListPage(offset: Int,
                          limit: Int,
                          returned: Int,
                          totalMatched: Int,
                          hasMore: Boolean) derives RW
