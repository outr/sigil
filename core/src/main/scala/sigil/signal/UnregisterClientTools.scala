package sigil.signal

import fabric.rw.*
import lightdb.id.Id
import sigil.conversation.Conversation

/**
 * Client→server [[Notice]]: withdraw UI interaction tools previously
 * registered via [[RegisterClientTools]]. `names = None` drops the
 * session's whole set for the conversation (a view closing); a
 * populated set narrows to those tools. Detach-driven cleanup happens
 * automatically at the transport layer — this Notice is for
 * mid-session withdrawal (the user navigated away from the screen
 * whose tools no longer apply).
 */
case class UnregisterClientTools(conversationId: Id[Conversation],
                                 sessionId: String,
                                 names: Option[Set[String]] = None) extends ConversationNotice derives RW
