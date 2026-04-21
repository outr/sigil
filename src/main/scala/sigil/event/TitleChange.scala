package sigil.event

import fabric.rw.*
import lightdb.id.Id
import lightdb.time.Timestamp
import lightdb.util.Nowish
import sigil.conversation.Conversation
import sigil.participant.ParticipantId
import sigil.signal.EventState

/**
 * Emitted when the LLM updates the conversation title as part of its response.
 *
 * UI-only — the model doesn't need to see prior title changes in its context.
 * Born `Active` as a signal to subscribers that they should apply the title
 * change now (animate transition, update displayed title, etc.). The server
 * then broadcasts a [[sigil.signal.StateDelta]] transitioning it to
 * `Complete`, at which point it's historical and replay-safe — no reactive
 * effects fire for it on subsequent reads.
 */
case class TitleChange(title: String,
                       participantId: ParticipantId,
                       conversationId: Id[Conversation],
                       state: EventState = EventState.Active,
                       visibility: Set[EventVisibility] = Set(EventVisibility.UI, EventVisibility.Model),
                       timestamp: Timestamp = Timestamp(Nowish()),
                       _id: Id[Event] = Event.id()) extends Event derives RW {
  override def withState(state: EventState): Event = copy(state = state)
}
