package sigil.signal

import fabric.rw.*
import lightdb.id.Id
import sigil.conversation.{Conversation, ConversationStatus}

/**
 * Server→client [[Notice]] broadcast when a conversation's app-defined
 * [[ConversationStatus]] changes (sigil #386), so history-sidebar UIs can
 * re-bucket the thread live instead of polling.
 *
 * Carries the full new status (via the polytype) so clients update their
 * cached entry wholesale. Conversation-scoped — delivered only to
 * subscribers watching this conversation. Idempotent; a consumer that
 * misses the pulse picks the change up on its next list refresh.
 *
 * Emitted from `Sigil.setConversationStatus`.
 */
case class ConversationStatusChanged(conversationId: Id[Conversation],
                                     status: ConversationStatus) extends ConversationNotice derives RW
