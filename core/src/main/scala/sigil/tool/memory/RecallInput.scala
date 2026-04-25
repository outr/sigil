package sigil.tool.memory

import fabric.rw.*
import sigil.SpaceId
import sigil.SpaceId.given
import sigil.tool.ToolInput

/**
 * Input for the `recall` tool. The agent calls this to retrieve
 * memories matching a query.
 *
 *   - `query`          — free-text; semantic when a vector index is
 *                         wired, substring otherwise.
 *   - `limit`          — cap on returned results.
 *   - `includeHistory` — when true, returns superseded (archived)
 *                         versions as well; defaults to false.
 *   - `spaces`         — optional scopes to search; when empty, the
 *                         tool falls back to
 *                         [[sigil.Sigil.defaultRecallSpaces]].
 */
case class RecallInput(query: String,
                       limit: Int = 10,
                       includeHistory: Boolean = false,
                       spaces: Set[SpaceId] = Set.empty)
  extends ToolInput derives RW
