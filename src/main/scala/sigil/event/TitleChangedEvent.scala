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
 * Atomic — created directly as `Complete`.
 */
case class TitleChangedEvent(title: String,
                             participantId: ParticipantId,
                             conversationId: Id[Conversation],
                             state: EventState = EventState.Complete,
                             visibility: Set[EventVisibility] = Set(EventVisibility.UI),
                             timestamp: Timestamp = Timestamp(Nowish()),
                             _id: Id[Event] = Event.id()) extends Event derives RW
