package sigil.tool.memory

import fabric.rw.*
import sigil.SpaceId
import sigil.SpaceId.given
import sigil.tool.ToolInput

/**
 * Input for the `recall_memory` tool — semantic search across the
 * agent's accessible memory spaces.
 *
 * @param query   natural-language query; embedding-backed when the
 *                Sigil instance is vector-wired, Lucene-fallback otherwise
 * @param spaces  optional explicit space scopes; omit to use the
 *                caller's default scope (typically the persona's)
 * @param limit   max number of results (default 10)
 */
case class RecallMemoryInput(query: String,
                             spaces: Set[SpaceId] = Set.empty,
                             limit: Int = 10)
  extends ToolInput derives RW
