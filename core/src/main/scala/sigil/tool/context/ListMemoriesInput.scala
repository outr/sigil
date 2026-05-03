package sigil.tool.context

import fabric.rw.*
import sigil.SpaceId
import sigil.SpaceId.given
import sigil.tool.ToolInput

/**
 * Input for the `list_memories` tool. Generalisation of
 * [[ListPinnedMemoriesInput]] — surfaces every memory the caller can
 * see, with optional filters for space subset, pinned status, and a
 * substring query. Paginated via `offset` + `limit`.
 *
 * @param spaces optional space filter; empty = every space the chain
 *               can access.
 * @param query  optional case-insensitive substring matched against
 *               key / label / summary / fact / tags. Empty = no filter.
 * @param pinned optional pinned-status filter. `None` = both pinned
 *               and unpinned; `Some(true)` = pinned only;
 *               `Some(false)` = unpinned only.
 * @param offset 0-based index into the filtered, sorted result set.
 *               The agent passes `offset = page * limit` to step
 *               through pages.
 * @param limit  max records on this page. Default 25 — small enough
 *               to fit comfortably in the next-turn context; agents
 *               needing more paginate explicitly. Hard-clamped to
 *               `[1, 100]` server-side.
 */
case class ListMemoriesInput(spaces: Set[SpaceId] = Set.empty,
                             query: Option[String] = None,
                             pinned: Option[Boolean] = None,
                             offset: Int = 0,
                             limit: Int = 25) extends ToolInput derives RW
