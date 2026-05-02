package sigil.signal

import fabric.rw.*
import lightdb.id.Id
import sigil.conversation.Conversation
import sigil.participant.ParticipantId

/**
 * Server→client [[Notice]] broadcast when a participant leaves a
 * conversation. Live viewers drop the participant from member lists
 * and any per-participant UI; chips referencing the id can stay
 * around as historical attribution but the live presence indicator
 * goes away.
 *
 * Only the [[ParticipantId]] is carried — clients keyed by id
 * already have whatever display info they cached at
 * [[ParticipantAdded]] / [[ParticipantUpdated]] time. Sending the
 * full record here would be redundant.
 *
 * Emitted from `Sigil.removeParticipant` after the DB upsert succeeds.
 */
case class ParticipantRemoved(conversationId: Id[Conversation],
                              participantId: ParticipantId) extends Notice derives RW
