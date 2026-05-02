package sigil.participant

import fabric.rw.PolyType
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
   * Human-readable name surfaced in UI chips, member lists, and any
   * "who said this" attribution. Concrete subtypes provide via either
   * a constructor parameter (a user profile carrying a real name) or
   * an `@serialized override def` deriving from another field
   * ([[DefaultAgentParticipant]] derives from `id`). The trait
   * declares it abstract so every subtype has to pick a strategy —
   * it must surface in the subtype's [[fabric.rw.RW]] for
   * [[fabric.define.DefType.Poly.commonFields]] to advertise it on
   * the abstract parent in cross-language codegen.
   */
  def displayName: String

  /**
   * Optional avatar URL surfaced alongside [[displayName]]. Same
   * abstract / `@serialized override` contract as [[displayName]] —
   * subtypes pick their strategy. Returning `None` is fine, but the
   * member must surface in the subtype's RW.
   */
  def avatarUrl: Option[String]

  /**
   * Reactive behavior — invoked by the framework dispatcher when one or
   * more new Events have appeared in a conversation this participant is
   * part of.
   *
   * Invocation is single-threaded per (participant, conversation) pair: the
   * dispatcher serializes calls via the participant's `AgentState(Active)`
   * claim, so two `process` invocations for the same participant in the
   * same conversation never overlap. Events that arrive during processing
   * are picked up by the dispatcher's self-loop on the next iteration.
   *
   * `triggers` is a lazy `Stream[Event]` — events that have arrived since
   * this participant last exited `process` and pass the framework's
   * `TriggerFilter` (Messages from others, tool results from the
   * participant's own prior work, atomic state events). Streaming deltas
   * and the participant's own AgentState Events are excluded by the
   * filter and never appear here.
   *
   * Single-pass by default; if a participant needs to inspect triggers
   * multiple times, materialize via `triggers.toList`. Because the stream
   * is lazy, a participant that ignores `triggers` pays no cost to load
   * them.
   *
   * Default: no-op. Override for agents (see [[AgentParticipant]]).
   */
  def process(context: TurnContext, triggers: Stream[Event]): Stream[Signal] = Stream.empty
}

object Participant extends PolyType[Participant]()(using scala.reflect.ClassTag(classOf[Participant]))
