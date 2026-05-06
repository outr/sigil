package sigil.conversation

import lightdb.id.Id
import sigil.participant.ParticipantId

/**
 * Transient DTO carrying a conversation's frames + per-participant
 * projections — the shape callers used to build via the now-retired
 * `db.views` collection (bug #26 deleted that). Re-exposed here as a
 * pure data object so test fixtures and one-off snapshot consumers
 * can keep their existing constructors.
 *
 * Not persisted, not a [[lightdb.doc.RecordDocument]], not registered
 * with any RW poly. The framework's hot path reads frames from
 * [[sigil.event.Event.contextFrame]] (via [[sigil.Sigil.framesFor]])
 * and projections from `db.participantProjections` (via
 * [[sigil.Sigil.projectionFor]]). This object is purely a convenience
 * for callers who want to bundle the two for a single function call.
 */
case class ConversationView(conversationId: Id[Conversation],
                            frames: Vector[ContextFrame] = Vector.empty,
                            participantProjections: Map[ParticipantId, ParticipantProjection] = Map.empty) {

  def projectionFor(id: ParticipantId): ParticipantProjection =
    participantProjections.getOrElse(id, ParticipantProjection.empty(id, conversationId))

  def updateParticipant(id: ParticipantId)(f: ParticipantProjection => ParticipantProjection): ConversationView =
    copy(participantProjections = participantProjections + (id -> f(projectionFor(id))))

  def aggregatedSkills(chain: List[ParticipantId]): Vector[ActiveSkillSlot] =
    chain.flatMap(id => projectionFor(id).activeSkills.values).toVector
}

object ConversationView {
  /** Stable id derivation kept for test fixtures that previously
    * looked up the persisted view by id. The framework no longer
    * persists views; callers that need a deterministic key for
    * arbitrary use can still derive one. */
  def idFor(conversationId: Id[Conversation]): Id[ConversationView] = Id(conversationId.value)
}
