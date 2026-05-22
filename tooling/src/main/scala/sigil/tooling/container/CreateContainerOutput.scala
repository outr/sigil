package sigil.tooling.container

import fabric.rw.*
import lightdb.id.Id
import sigil.tool.output.ToolOutputNode

/**
 * Result of [[CreateContainerTool]]. The `itemsId` (which is the
 * synthetic `callId` for the persisted rows) is what consumer
 * tools pass into their `itemsId: Id[ToolOutputNode]` field.
 *
 *   - `itemsId`   — opaque container id. The framework uses it as
 *     the row's `callId.value` so `next_page(itemsId)` and
 *     consumer tools' `itemsId` both resolve through the same
 *     persisted rows.
 *   - `itemCount` — total number of rows the call materialised.
 */
case class CreateContainerOutput(itemsId: Id[ToolOutputNode],
                                 itemCount: Int)
  extends sigil.tool.ToolOutput derives RW
