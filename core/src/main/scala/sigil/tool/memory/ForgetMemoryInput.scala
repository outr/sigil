package sigil.tool.memory

import fabric.rw.*
import lightdb.id.Id
import sigil.conversation.ContextMemory
import sigil.tool.ToolInput

/**
 * Input for the `forget_memory` tool. Mark a previously stored memory
 * as rejected — kept on disk for lineage but hidden from recall.
 *
 * Either `memoryId` (precise — drop one specific record) or `key`
 * (drop all records under a key) must be provided. Supplying both is
 * an error.
 */
case class ForgetMemoryInput(memoryId: Option[Id[ContextMemory]] = None,
                             key: Option[String] = None,
                             reason: Option[String] = None)
  extends ToolInput derives RW
