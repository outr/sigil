package sigil.signal

import fabric.rw.*
import lightdb.id.Id
import sigil.conversation.Conversation
import sigil.participant.ParticipantId

/**
 * Server→client [[Notice]] broadcast when a new conversation is created.
 * Lets every connected viewer's UI update its conversation list panel
 * incrementally — no need to re-fetch via [[RequestConversationList]].
 *
 * Emitted from `Sigil.newConversation` after the conversation row +
 * initial topic are persisted. Carries the new conversation's id and
 * the participant who created it; clients can fetch full state via
 * [[SwitchConversation]] when the user opens it.
 */
case class ConversationCreated(conversationId: Id[Conversation],
                               createdBy: ParticipantId) extends Notice derives RW
