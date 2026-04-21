package sigil.conversation

import fabric.rw.*
import lightdb.doc.{JsonConversion, RecordDocument, RecordDocumentModel}
import lightdb.id.Id
import lightdb.time.Timestamp
import sigil.participant.ParticipantId

/**
 * A per-conversation materialized projection of the event log: the
 * rolling window of render-ready [[ContextFrame]]s plus per-participant
 * projections (active skills, recent/suggested tools).
 *
 * Maintained incrementally inside the same transaction that writes events
 * via `Sigil.publish`. Curators point-lookup this record each turn and hand
 * it to the provider instead of re-scanning the event log.
 *
 * The view's id is derived from the conversation's id so the lookup is a
 * trivial O(1) fetch.
 *
 */
case class ConversationView(conversationId: Id[Conversation],
                            frames: Vector[ContextFrame] = Vector.empty,
                            participantProjections: Map[ParticipantId, ParticipantProjection] = Map.empty,
                            created: Timestamp = Timestamp(),
                            modified: Timestamp = Timestamp(),
                            _id: Id[ConversationView] = ConversationView.id())
  extends RecordDocument[ConversationView] {

  /** Return the projection for a participant, defaulting to an empty one. */
  def projectionFor(id: ParticipantId): ParticipantProjection =
    participantProjections.getOrElse(id, ParticipantProjection())

  /** Apply a transform to a participant's projection, creating one if needed. */
  def updateParticipant(id: ParticipantId)(f: ParticipantProjection => ParticipantProjection): ConversationView =
    copy(participantProjections = participantProjections + (id -> f(projectionFor(id))))

  /** Flat list of every active skill contributed by any participant in the
    * given chain. Same aggregation policy as the pre-refactor
    * `TurnInput.aggregatedSkills` delegates here. */
  def aggregatedSkills(chain: List[ParticipantId]): Vector[ActiveSkillSlot] =
    chain.flatMap(id => projectionFor(id).activeSkills.values).toVector
}

object ConversationView extends RecordDocumentModel[ConversationView] with JsonConversion[ConversationView] {
  implicit override def rw: RW[ConversationView] = RW.gen

  /** Derive the view's id from its conversation's id — 1:1 relationship. */
  def idFor(conversationId: Id[Conversation]): Id[ConversationView] = Id(conversationId.value)

  override def id(value: String = rapid.Unique()): Id[ConversationView] = Id(value)
}
