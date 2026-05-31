package sigil.tool.output

import fabric.rw.*
import lightdb.id.Id
import sigil.conversation.Conversation
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
 *   - `conversationId` — sigil #289 — target a specific
 *     conversation for the read. When unset (default), the caller's
 *     current conversation is used. Cross-conversation reads are
 *     gated by [[sigil.Sigil.canReadConversation]] — typically
 *     allowed for the caller's parent or worker conversations.
 */
case class NextPageInput(referenceId: String,
                         page: Int = 0,
                         pageSize: Int = 50,
                         conversationId: Option[Id[Conversation]] = None) extends ToolInput derives RW
