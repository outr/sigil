package sigil.tool.model

import fabric.rw.*
import lightdb.id.Id
import sigil.conversation.{Conversation, Topic}
import sigil.tool.ToolInput

/**
 * Input for the `search_conversation` tool. The agent calls this to
 * retrieve historical events from the persistent event log when the
 * rolling context has been trimmed or when older detail is needed
 * mid-conversation.
 *
 *   - `query` — free-text search; vector-embedded when vector search is
 *     wired, substring-matched via the Lucene-backed events store
 *     otherwise. Empty / blank query (sigil #289) switches the tool
 *     into chronological-walk mode for the target conversation.
 *   - `topicId` — restrict to a single topic (optional).
 *   - `limit` — cap on returned results; default 10.
 *   - `conversationId` — sigil #289 — target a specific conversation
 *     for the read. When unset (default), the caller's current
 *     conversation is used. When set, the framework enforces the
 *     parent / worker access predicate (see
 *     [[sigil.Sigil.canReadConversation]]) — agents can read their
 *     own conversation, their parent conversation, or any of their
 *     workers' conversations.
 */
case class SearchConversationInput(query: String,
                                   topicId: Option[Id[Topic]] = None,
                                   limit: Int = 10,
                                   conversationId: Option[Id[Conversation]] = None,
                                   /**
                                    * Zero-indexed page for walk mode (empty query).
                                    * Page N skips `N * limit` events from the start. Ignored
                                    * in search mode (search returns ranked top-N hits, not
                                    * paginated chronological output).
                                    */
                                   page: Int = 0)
  extends ToolInput derives RW
