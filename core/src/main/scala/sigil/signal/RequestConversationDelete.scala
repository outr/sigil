package sigil.signal

import fabric.rw.*
import lightdb.id.Id
import sigil.conversation.Conversation

/**
 * Sigil #300 — client→server [[Notice]]: "delete this conversation."
 * The default [[sigil.Sigil.handleNotice]] arm invokes
 * [[sigil.Sigil.deleteConversation]], which hard-deletes the
 * conversation row + every Event + the participant projections +
 * encoded-context caches, and broadcasts the authoritative
 * [[ConversationDeleted]] back to every viewer (before the cascade so
 * live viewers see the pulse while the SignalHub is still wired).
 *
 * Mirrors the [[RequestConversationList]] / [[ConversationListSnapshot]]
 * pattern: the inbound `Request*` Notice is the verb; the outbound
 * non-`Request*` Notice ([[ConversationDeleted]]) is the broadcast
 * confirmation.
 */
case class RequestConversationDelete(conversationId: Id[Conversation]) extends Notice derives RW
