package sigil.event

import fabric.rw.*
import lightdb.id.Id
import lightdb.time.Timestamp
import lightdb.util.Nowish
import sigil.conversation.Conversation
import sigil.participant.ParticipantId
import sigil.provider.TokenUsage
import sigil.signal.EventState
import sigil.tool.model.ResponseContent

/**
 * A message from a participant — user input, agent output, or system message.
 * The participantId identifies who sent it; the content carries structured blocks.
 *
 * Created `Active` by streaming-producing tools (content populated via
 * `MessageDelta`); transitions to `Complete` when the producer signals end of
 * stream. Atomic Messages — e.g. a user typing a one-shot message or a
 * `FindCapabilityTool` result — are created directly as `Complete`.
 */
case class Message(participantId: ParticipantId,
                   conversationId: Id[Conversation],
                   content: Vector[ResponseContent] = Vector.empty,
                   usage: TokenUsage = TokenUsage(0, 0, 0),
                   state: EventState = EventState.Active,
                   timestamp: Timestamp = Timestamp(Nowish()),
                   _id: Id[Event] = Event.id()) extends Event derives RW {
  override def withState(state: EventState): Event = copy(state = state)
}
