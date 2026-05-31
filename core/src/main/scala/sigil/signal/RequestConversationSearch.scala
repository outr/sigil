package sigil.signal

import fabric.rw.*
import lightdb.id.Id
import sigil.conversation.{Conversation, Topic}

/**
 * Client→server [[Notice]]: "search my conversation events for `query`."
 * The server's [[sigil.Sigil.handleNotice]] default-replies with a
 * [[ConversationSearchSnapshot]] targeted at the requesting viewer.
 *
 * Mirrors the agent-side [[sigil.tool.util.SearchConversationTool]]
 * surface for UI consumers: search panels, history-filter widgets,
 * "find that thing I said last week" affordances. Uses the same
 * underlying [[sigil.Sigil.searchConversationEvents]] primitive — so
 * UI results match agent results exactly.
 *
 * Fire-and-forget — the UI shows a loading indicator and reacts when
 * the snapshot arrives. There's no correlation id; the snapshot carries
 * `query` so a UI in flight can match the response to its current input.
 *
 *   - `query` — search text. Empty / blank means "walk mode" is not
 *     supported on the UI surface; pass a non-empty string.
 *   - `conversationId` — optional; defaults to the viewer's current
 *     conversation. Cross-conversation reads are subject to the same
 *     [[sigil.Sigil.canReadConversation]] gating as the agent tool.
 *   - `topicId` — optional; restrict to a single topic.
 *   - `limit` — max hits (default 20).
 */
case class RequestConversationSearch(query: String,
                                     conversationId: Option[Id[Conversation]] = None,
                                     topicId: Option[Id[Topic]] = None,
                                     limit: Int = 20) extends Notice derives RW {
  override def conversationScope: Option[Id[Conversation]] = conversationId
}
