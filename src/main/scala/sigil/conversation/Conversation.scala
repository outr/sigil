package sigil.conversation

import fabric.rw.*
import lightdb.doc.{JsonConversion, RecordDocument, RecordDocumentModel}
import lightdb.id.Id
import lightdb.time.Timestamp
import rapid.Unique
import sigil.participant.ParticipantId
import sigil.provider.Mode

/**
 * A conversation is a durable scope for events, the participants involved,
 * and any per-conversation metadata. Stored in [[sigil.db.SigilDB.conversations]].
 *
 * `participantIds` drives the dispatcher's fan-out: when a Signal lands, the
 * framework looks up the conversation, resolves participants via
 * [[sigil.Sigil.participantsFor]], and fires the ones whose `TriggerFilter`
 * predicate matches.
 *
 * `currentMode` is the conversation's active operating mode, kept up to
 * date by the framework as [[sigil.event.ModeChange]] events land. All
 * agents acting on this conversation read this field for their next
 * provider request — mode is conversation-level state, not agent-level.
 *
 * `RecordDocument` brings `created` / `modified` timestamps — useful for
 * "last activity" sorting in UIs.
 */
case class Conversation(participantIds: List[ParticipantId] = Nil,
                        title: Option[String] = None,
                        currentMode: Mode = Mode.Conversation,
                        created: Timestamp = Timestamp(),
                        modified: Timestamp = Timestamp(),
                        _id: Id[Conversation] = Conversation.id())
  extends RecordDocument[Conversation] {

  /** Convenience alias for `_id`. */
  def id: Id[Conversation] = _id
}

object Conversation extends RecordDocumentModel[Conversation] with JsonConversion[Conversation] {
  implicit override def rw: RW[Conversation] = RW.gen

  override def id(value: String = Unique()): Id[Conversation] = Id(value)
}
