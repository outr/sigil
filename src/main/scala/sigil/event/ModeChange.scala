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
 * determine the `currentMode` on the next provider request.
 *
 * Born `Active` so subscribers receive a pulse to react on (UI mode
 * indicators, skill-slot swaps, audio cues). The framework then
 * broadcasts a `StateDelta` transitioning it to `Complete`, at which
 * point it's historical — replay renders state silently with no
 * reactive effects. `Sigil.updateConversationProjection` and
 * `Sigil.maybeApplyModeSkill` both act on the `Complete` settle, not
 * the `Active` pulse, so `Conversation.currentMode` and the Mode-source
 * skill slot update exactly once per transition.
 */
case class ModeChange(mode: Mode,
                      participantId: ParticipantId,
                      conversationId: Id[Conversation],
                      reason: Option[String] = None,
                      state: EventState = EventState.Active,
                      timestamp: Timestamp = Timestamp(Nowish()),
                      _id: Id[Event] = Event.id()) extends Event derives RW {
  override def withState(state: EventState): Event = copy(state = state)
}
