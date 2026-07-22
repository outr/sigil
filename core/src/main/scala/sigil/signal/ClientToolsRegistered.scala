package sigil.signal

import fabric.rw.*
import lightdb.id.Id
import sigil.conversation.Conversation

/**
 * Server→client [[Notice]]: acknowledgment of a
 * [[RegisterClientTools]] request. `accepted` lists the tool names now
 * live; `rejected` maps each refused name to its reason (invalid
 * name, server-tool collision, app filter, registration limit) — so
 * the frontend developer sees WHY a tool didn't land instead of
 * silently missing it from discovery.
 */
case class ClientToolsRegistered(conversationId: Id[Conversation],
                                 sessionId: String,
                                 accepted: List[String],
                                 rejected: Map[String, String] = Map.empty) extends ConversationNotice derives RW
