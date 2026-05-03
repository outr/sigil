package sigil.tool.model

import fabric.rw.*
import sigil.SpaceId
import sigil.SpaceId.given
import sigil.tool.ToolInput

/**
 * Input for `semantic_search` — the unified memory-retrieval tool.
 *
 *   - `query`          — natural-language; embedding-ranked when a
 *                        vector index is wired, Lucene/substring
 *                        fallback otherwise.
 *   - `limit`          — max number of results (default 10).
 *   - `includeHistory` — when true, returns superseded (archived)
 *                        versions in addition to the current one
 *                        (default false).
 *   - `spaces`         — explicit scopes to search; when empty, the
 *                        tool falls back to
 *                        [[sigil.Sigil.defaultRecallSpaces]].
 */
case class SemanticSearchInput(query: String,
                               limit: Int = 10,
                               includeHistory: Boolean = false,
                               spaces: Set[SpaceId] = Set.empty) extends ToolInput derives RW
