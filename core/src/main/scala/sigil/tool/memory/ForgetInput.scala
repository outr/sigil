package sigil.tool.memory

import fabric.rw.*
import sigil.SpaceId
import sigil.SpaceId.given
import sigil.tool.ToolInput

/**
 * Input for the `forget` tool. Hard-deletes every version of a keyed
 * memory in the given space (or the caller's default space).
 */
case class ForgetInput(key: String,
                       spaceId: Option[SpaceId] = None)
  extends ToolInput derives RW
