package sigil.tool.output

import fabric.rw.*
import sigil.tool.ToolInput

/**
 * Navigate the next page of a paginated tool result.
 *
 *   - `referenceId` — the parent node id, OR the originating
 *     tool-call id for the top-level page. The first-page emission
 *     of a [[PaginatedTool]] surfaces both via `callId` and per-row
 *     `nodeIds`; the agent picks which level it wants to page.
 *   - `page` — zero-indexed page number. Page 0 reads rows
 *     `[0, pageSize)`, page 1 reads `[pageSize, 2*pageSize)`, etc.
 *   - `pageSize` — defaults to 50. Cap is 500 (the framework
 *     truncates higher values silently).
 */
case class NextPageInput(referenceId: String,
                         page: Int = 0,
                         pageSize: Int = 50) extends ToolInput derives RW
