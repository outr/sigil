package sigil.signal

import fabric.rw.*
import lightdb.id.Id
import sigil.conversation.Conversation
import sigil.participant.Participant

/**
 * Server→client [[Notice]] broadcast when a participant's display
 * info or other publicly-visible fields change — the agent's model
 * card swap, a user's name / avatar update, etc.
 *
 * Carries the full [[Participant]] (via the polytype) so clients can
 * replace their cached entry wholesale rather than diffing against a
 * partial update. Idempotent; consumers that miss the pulse pick up
 * the change on their next list refresh
 * ([[RequestConversationList]] / participant inspection).
 *
 * Emitted from `Sigil.updateParticipant`.
 */
case class ParticipantUpdated(conversationId: Id[Conversation],
                              participant: Participant) extends Notice derives RW
