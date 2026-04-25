package sigil.tool.memory

import fabric.rw.*
import sigil.SpaceId
import sigil.SpaceId.given
import sigil.tool.ToolInput

/**
 * Input for the `memory_history` tool. Surfaces the full version
 * history of a keyed memory — useful when the agent needs to
 * understand how a fact has evolved.
 */
case class MemoryHistoryInput(key: String,
                              spaceId: Option[SpaceId] = None)
  extends ToolInput derives RW
