package sigil.event

import fabric.rw.*
import lightdb.id.Id
import lightdb.time.Timestamp
import lightdb.util.Nowish
import sigil.conversation.Conversation
import sigil.participant.ParticipantId
import sigil.signal.EventState

/**
 * Emitted when an agent renames the conversation via the `title` field
 * on a `respond` call (see [[sigil.tool.core.RespondTool]]).
 *
 * Born `Active` so subscribers receive a pulse to react on (UI title
 * flash/animation). The framework then broadcasts a `StateDelta`
 * transitioning it to `Complete`, at which point it's historical —
 * replay is silent. `Sigil.updateConversationProjection` writes
 * `Conversation.title` on the `Complete` settle, not the `Active`
 * pulse, so no double-writes occur. Same-title submissions are
 * suppressed upstream so this event only fires on an actual rename.
 */
case class TitleChange(title: String,
                       participantId: ParticipantId,
                       conversationId: Id[Conversation],
                       state: EventState = EventState.Active,
                       timestamp: Timestamp = Timestamp(Nowish()),
                       _id: Id[Event] = Event.id())
  extends Event derives RW {
  override def withState(state: EventState): Event = copy(state = state)
}
