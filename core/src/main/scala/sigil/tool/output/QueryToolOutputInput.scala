package sigil.tool.output

import fabric.rw.*
import lightdb.id.Id
import sigil.conversation.Conversation
import sigil.tool.ToolInput

/**
 * Cross-tree query over a single tool-call's paginated output.
 * Used when the agent wants a filtered or sorted view that
 * `next_page` alone can't express — "all files with >10 matches",
 * "the top-level node with the highest count", "any node whose
 * payload mentions 'reset_password'".
 *
 *   - `callId` — the originating tool-call's id. Scopes the
 *     query to that call's rows.
 *   - `containsText` — case-insensitive substring filter over the
 *     row's rendered JSON. `None` returns every row.
 *   - `level` — filter by tree level. `Some(0)` returns
 *     top-level nodes only; `Some(1)` returns direct children
 *     only; `None` returns rows at any level.
 *   - `page` / `pageSize` — pagination over the filtered set.
 *     Defaults: page 0, pageSize 50. Max pageSize 500.
 *   - `conversationId` — sigil #289 — target a specific
 *     conversation for the read. When unset (default), the caller's
 *     current conversation is used. Cross-conversation reads are
 *     gated by [[sigil.Sigil.canReadConversation]] — typically
 *     allowed for the caller's parent or worker conversations.
 */
case class QueryToolOutputInput(callId: String,
                                containsText: Option[String] = None,
                                level: Option[Int] = None,
                                page: Int = 0,
                                pageSize: Int = 50,
                                conversationId: Option[Id[Conversation]] = None) extends ToolInput derives RW
