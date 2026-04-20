package sigil.participant

import rapid.Stream
import sigil.TurnContext
import sigil.event.Event
import sigil.signal.Signal

/**
 * A participant in a conversation. May be a human, an agent, a system
 * process, or any app-defined subtype. The base trait carries an identity
 * and a reactive `process` hook — by default a no-op, overridden by active
 * participants (typically [[AgentParticipant]]) that take action in response
 * to new Events in the conversation.
 */
trait Participant {
  def id: ParticipantId

  /**
   * Reactive behavior — invoked by an app-level dispatcher when one or more
   * new Events have appeared in a conversation this participant is part of.
   *
   * Invocation is single-threaded per participant: the dispatcher guarantees
   * only one `process` call for this participant is in flight at a time, and
   * any Events that arrive during processing accumulate into the next
   * invocation's `triggers` snapshot.
   *
   * `triggers` contains the Events that have arrived since this participant
   * last exited `process` — Messages from others, tool results from the
   * participant's own prior work that unblock continuation, atomic state
   * events, etc. Streaming deltas and the participant's own AgentState
   * Events are excluded.
   *
   * Default: no-op. Override for agents (see [[AgentParticipant]]).
   */
  def process(context: TurnContext, triggers: List[Event]): Stream[Signal] = Stream.empty
}
