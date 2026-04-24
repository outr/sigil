package sigil.tool.memory

import fabric.rw.*
import sigil.conversation.MemorySpaceId
import sigil.conversation.MemorySpaceId.given
import sigil.tool.ToolInput

/**
 * Input for the `memory_history` tool. Surfaces the full version
 * history of a keyed memory — useful when the agent needs to
 * understand how a fact has evolved.
 */
case class MemoryHistoryInput(key: String,
                              spaceId: Option[MemorySpaceId] = None)
  extends ToolInput derives RW
