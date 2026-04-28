package sigil.signal

import fabric.rw.*
import lightdb.id.Id
import sigil.conversation.Conversation

/**
 * Server→client [[Notice]] broadcast when a conversation is deleted.
 * Live viewers get the pulse via the standard stream and can drop the
 * conversation from their list panels.
 *
 * Emitted from `Sigil.deleteConversation` BEFORE the cascade runs (so
 * it goes out via the SignalHub before the conversation's records
 * are wiped). Reconnecting clients won't see it — the conversation
 * simply isn't in their replay anymore, which is the correct
 * "doesn't exist" semantic.
 */
case class ConversationDeleted(conversationId: Id[Conversation]) extends Notice derives RW
