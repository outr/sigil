package sigil.tool.memory

import fabric.rw.*
import sigil.conversation.MemorySpaceId
import sigil.conversation.MemorySpaceId.given
import sigil.tool.ToolInput

/**
 * Input for the `forget` tool. Hard-deletes every version of a keyed
 * memory in the given space (or the caller's default space).
 */
case class ForgetInput(key: String,
                       spaceId: Option[MemorySpaceId] = None)
  extends ToolInput derives RW
