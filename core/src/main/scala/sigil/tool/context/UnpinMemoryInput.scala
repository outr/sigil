package sigil.tool.context

import fabric.rw.*
import sigil.SpaceId
import sigil.SpaceId.given
import sigil.tool.ToolInput

/**
 * Input for the `unpin_memory` tool. Flips
 * [[sigil.conversation.ContextMemory.pinned]] from `true` to `false`
 * so the memory stops being rendered every turn. The record is NOT
 * deleted — the agent / user can re-pin (via `Sigil.persistMemory` /
 * app's UI) later, and topical retrieval will still surface the
 * memory when keywords match.
 *
 * @param key   the memory's `key` (preferred) or `_id` value if no key.
 * @param space optional disambiguator when the same key is pinned in
 *              multiple accessible spaces.
 */
case class UnpinMemoryInput(key: String,
                            space: Option[SpaceId] = None) extends ToolInput derives RW
