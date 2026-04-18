package sigil.event

import fabric.rw.*
import lightdb.id.Id
import lightdb.time.Timestamp
import sigil.participant.ParticipantId
import sigil.tool.model.ResponseContent

/**
 * A message from a participant — user input, agent output, or system message.
 * The participantId identifies who sent it; the content carries structured blocks.
 *
 * Default visibility is both UI (rendered to users) and Model (included in
 * subsequent turns' context).
 */
case class Message(
  participantId: ParticipantId,
  content: Vector[ResponseContent],
  visibility: Set[EventVisibility] = Set(EventVisibility.UI, EventVisibility.Model),
  timestamp: Timestamp = Timestamp(),
  id: Id[Event] = Event.id()
) extends Event derives RW
