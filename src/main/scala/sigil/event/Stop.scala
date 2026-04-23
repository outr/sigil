package sigil.event

import fabric.rw.*
import lightdb.id.Id
import lightdb.time.Timestamp
import lightdb.util.Nowish
import sigil.conversation.{Conversation, Topic}
import sigil.participant.ParticipantId
import sigil.signal.EventState

/**
 * Halt the current turn for one or all agents participating in a
 * conversation. Used both by humans ("Stop" button in a UI) and by other
 * agents (e.g. a monitor agent that spots a peer going down the wrong
 * path or attempting something destructive).
 *
 *   - `targetParticipantId = None` — request every agent in the
 *     conversation to stop
 *   - `targetParticipantId = Some(id)` — target a single agent
 *   - `force = false` (default) — graceful: no further iterations start
 *     after the current one finishes. The in-flight stream completes.
 *   - `force = true` — interrupt the in-flight streaming provider call
 *     mid-flight. Use sparingly; cleanest for "monitor stops peer
 *     attempting destructive action" scenarios.
 *
 * Emitted via [[sigil.tool.core.StopTool]] (for LLM-initiated stops) or
 * directly through [[sigil.Sigil.publish]] (for UI-initiated stops). Not
 * a trigger — agents do not wake on a `Stop`; it's a control signal
 * consumed by the dispatcher.
 *
 * Born `Active` so subscribers react (UI "stopping..." indicator, the
 * dispatcher's stop-flag set) and then settles to `Complete` via a
 * `StateDelta` — on replay, historical stops fire no effects.
 */
case class Stop(participantId: ParticipantId,
                conversationId: Id[Conversation],
                topicId: Id[Topic],
                targetParticipantId: Option[ParticipantId] = None,
                force: Boolean = false,
                reason: Option[String] = None,
                state: EventState = EventState.Active,
                timestamp: Timestamp = Timestamp(Nowish()),
                _id: Id[Event] = Event.id())
  extends Event derives RW {
  override def withState(state: EventState): Event = copy(state = state)
}
