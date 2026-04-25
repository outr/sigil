package sigil.tool.model

import fabric.rw.*
import lightdb.id.Id
import sigil.conversation.Topic
import sigil.tool.ToolInput

/**
 * Input for the `search_conversation` tool. The agent calls this to
 * retrieve historical events from the persistent event log when the
 * rolling context has been trimmed or when older detail is needed
 * mid-conversation.
 *
 *   - `query` — free-text search; vector-embedded when vector search is
 *     wired, substring-matched via the Lucene-backed events store
 *     otherwise.
 *   - `topicId` — restrict to a single topic (optional).
 *   - `limit` — cap on returned results; default 10.
 *
 * Conversation is scoped implicitly to the caller's current
 * conversation — a `search_conversation` call can never leak across
 * conversations.
 */
case class SearchConversationInput(query: String,
                                   topicId: Option[Id[Topic]] = None,
                                   limit: Int = 10) extends ToolInput derives RW
