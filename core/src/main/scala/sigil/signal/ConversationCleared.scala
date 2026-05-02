package sigil.signal

import fabric.rw.*
import lightdb.id.Id
import lightdb.time.Timestamp
import sigil.conversation.Conversation
import sigil.participant.ParticipantId

/**
 * Server→client [[Notice]] broadcast when a conversation's history is
 * cleared — the conversation row, participants, and current topic
 * remain alive, but the message list resets to empty for UI
 * purposes. Distinct from [[ConversationDeleted]] (which removes the
 * conversation entirely) and from `TopicChange` (which forks to a
 * new topic but leaves the old topic's events reachable).
 *
 * Live viewers get the pulse via the standard stream and reset their
 * chat panel to empty. Reconnecting clients pick up the post-clear
 * state automatically — the framework's view rebuild and signal
 * replay both honor the conversation's `clearedAt` watermark, so
 * cleared events stay out of the projection without needing to be
 * deleted from `db.events` (which keeps the audit trail intact).
 *
 * Emitted from `Sigil.clearConversation`. The events themselves
 * remain in `SigilDB.events` until a separate retention pass (or a
 * `ConversationDeleted`) removes them.
 */
case class ConversationCleared(conversationId: Id[Conversation],
                               clearedAt: Timestamp,
                               clearedBy: ParticipantId) extends Notice derives RW
