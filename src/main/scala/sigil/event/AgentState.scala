package sigil.event

import fabric.rw.*
import lightdb.id.Id
import lightdb.time.Timestamp
import lightdb.util.Nowish
import sigil.conversation.Conversation
import sigil.participant.{AgentParticipantId, ParticipantId}
import sigil.signal.{AgentActivity, EventState}

/**
 * Lifecycle marker for an agent's turn. Emitted by the agent when it begins
 * working on a conversation; its `activity` field transitions Thinking →
 * Typing → (possibly repeat) → Idle via [[sigil.signal.AgentStateDelta]] as
 * the agent's work progresses.
 *
 * Spans the agent's entire turn, including chained orchestrator invocations.
 * The first invocation emits it (Active, Thinking). Subsequent invocations in
 * the chain mutate `activity` via deltas. The terminal invocation transitions
 * both `activity = Idle` and `state = Complete` in one delta, signaling the
 * turn is done.
 *
 * Consumers:
 *   - UI subscribers render activity indicators (spinner, cursor, nothing).
 *   - HTTP SSE controllers close the stream when all AgentStates in the
 *     conversation reach `Complete` / `Idle`.
 *   - Replay: historical AgentStates are Complete, so no reactive indicators
 *     fire — the UI converges to "no agent working" silently.
 */
case class AgentState(agentId: AgentParticipantId,
                      activity: AgentActivity = AgentActivity.Thinking,
                      participantId: ParticipantId,
                      conversationId: Id[Conversation],
                      state: EventState = EventState.Active,
                      visibility: Set[EventVisibility] = Set(EventVisibility.UI),
                      timestamp: Timestamp = Timestamp(Nowish()),
                      _id: Id[Event] = Event.id()) extends Event derives RW {
  override def withState(state: EventState): Event = copy(state = state)
}
