package sigil.event

import fabric.rw.*
import lightdb.id.Id
import lightdb.time.Timestamp
import lightdb.util.Nowish
import sigil.conversation.Conversation
import sigil.participant.ParticipantId
import sigil.provider.Mode
import sigil.signal.EventState

/**
 * Emitted when the agent transitions to a different operating mode.
 *
 * Orchestrators inspect the latest `ModeChange` in the conversation to
 * determine the `currentMode` on the next provider request. Visible to both
 * UI (for display) and Model (so the LLM sees past transitions in subsequent
 * turns).
 *
 * Born `Active` as a signal to subscribers that they should apply the mode
 * change now (update mode indicators, swap active skill set, etc.). The
 * server then broadcasts a [[sigil.signal.StateDelta]] transitioning it to
 * `Complete`, at which point it's historical — replay renders state silently
 * with no reactive effects.
 */
case class ModeChange(mode: Mode,
                      participantId: ParticipantId,
                      conversationId: Id[Conversation],
                      reason: Option[String] = None,
                      state: EventState = EventState.Active,
                      visibility: Set[EventVisibility] = Set(EventVisibility.UI, EventVisibility.Model),
                      timestamp: Timestamp = Timestamp(Nowish()),
                      _id: Id[Event] = Event.id())
  extends Event derives RW {
  override def withState(state: EventState): Event = copy(state = state)
}
