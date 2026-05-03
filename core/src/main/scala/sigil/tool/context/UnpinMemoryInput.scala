package sigil.tool.context

import fabric.rw.*
import sigil.SpaceId
import sigil.SpaceId.given
import sigil.tool.ToolInput

/**
 * Input for the `unpin_memory` tool. Demotes a [[sigil.conversation.MemorySource.Critical]]
 * memory to [[sigil.conversation.MemorySource.Compression]] so it stops
 * being rendered every turn. The record is NOT deleted — the agent /
 * user can re-pin (via `Sigil.persistMemory` / app's UI) later.
 *
 * @param key   the memory's `key` (preferred) or `_id` value if no key.
 * @param space optional disambiguator when the same key is pinned in
 *              multiple accessible spaces.
 */
case class UnpinMemoryInput(key: String,
                            space: Option[SpaceId] = None) extends ToolInput derives RW
