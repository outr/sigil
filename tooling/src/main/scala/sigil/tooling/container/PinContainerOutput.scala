package sigil.tooling.container

import fabric.rw.*
import lightdb.id.Id
import sigil.tool.output.ToolOutputNode

/**
 * Result of [[PinContainerTool]] / [[UnpinContainerTool]].
 *
 *   - `itemsId`      — the container the pin state changed on.
 *   - `rowsAffected` — number of rows whose flag flipped.
 */
case class PinContainerOutput(itemsId: Id[ToolOutputNode],
                              rowsAffected: Int)
  extends sigil.tool.ToolOutput derives RW
