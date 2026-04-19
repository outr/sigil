package sigil.event

import fabric.rw.*
import lightdb.id.Id
import lightdb.time.Timestamp
import sigil.conversation.Conversation
import sigil.participant.ParticipantId
import sigil.provider.Mode
import sigil.signal.EventState

/**
 * Emitted when the agent transitions to a different operating mode.
 *
 * Orchestrators inspect the latest `ModeChangedEvent` in the conversation
 * to determine the `currentMode` on the next provider request. Visible to
 * both UI (for display) and Model (so the LLM sees past transitions in
 * subsequent turns).
 *
 * Atomic — created directly as `Complete`.
 */
case class ModeChangedEvent(mode: Mode,
                            participantId: ParticipantId,
                            conversationId: Id[Conversation],
                            reason: Option[String] = None,
                            state: EventState = EventState.Complete,
                            visibility: Set[EventVisibility] = Set(EventVisibility.UI, EventVisibility.Model),
                            timestamp: Timestamp = Timestamp(),
                            id: Id[Event] = Event.id()) extends Event derives RW
