package sigil.tooling.container

import fabric.rw.*
import lightdb.id.Id
import sigil.tool.ToolInput
import sigil.tool.output.ToolOutputNode

/**
 * Input for [[PinContainerTool]] / [[UnpinContainerTool]] —
 * toggle the `pinned` flag on every row of a container so the
 * conversation-level cleanup skips it (pin) or resumes counting
 * it for age / size pruning (unpin).
 */
case class PinContainerInput(itemsId: Id[ToolOutputNode]) extends ToolInput derives RW
