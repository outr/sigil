package sigil.signal

import fabric.rw.*
import lightdb.id.Id
import sigil.conversation.Conversation
import sigil.participant.Participant

/**
 * Server→client [[Notice]] broadcast when a participant joins a
 * conversation. Live viewers update their member list, sidebar chips,
 * and any per-participant UI.
 *
 * Carries the full [[Participant]] (via the `Participant` polytype)
 * so consumers don't need a separate `displayName` / `avatarUrl`
 * lookup — the participant's display info travels with the lifecycle
 * pulse. Reused by [[ParticipantUpdated]] when display info changes.
 *
 * Emitted from `Sigil.addParticipant` after the DB upsert succeeds.
 */
case class ParticipantAdded(conversationId: Id[Conversation],
                            participant: Participant) extends Notice derives RW
