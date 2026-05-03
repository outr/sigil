package sigil.tool.context

import fabric.rw.*
import sigil.SpaceId
import sigil.SpaceId.given
import sigil.tool.ToolInput

/**
 * Input for the `list_pinned_memories` tool — returns every Critical
 * memory the caller's chain can access, with key + summary + per-record
 * token cost. Used by agents (typically prompted by the user) to
 * walk through pinned directives and decide what to unpin.
 *
 * @param spaces optional filter — limit listing to a subset of the
 *               caller's accessible spaces. Empty = all accessible.
 */
case class ListPinnedMemoriesInput(spaces: Set[SpaceId] = Set.empty) extends ToolInput derives RW
