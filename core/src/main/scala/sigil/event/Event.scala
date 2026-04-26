package sigil.event

import fabric.rw.RW
import lightdb.doc.{Document, JsonConversion}
import lightdb.id.Id
import lightdb.time.Timestamp
import sigil.conversation.{Conversation, Topic}
import sigil.participant.ParticipantId
import sigil.signal.{EventState, Signal}

/**
 * A durable, stateful record in the conversation log. Every Event:
 *
 *   - belongs to a single `conversationId`
 *   - belongs to a single `topicId` within that conversation (the active
 *     thread at emission time); used for search and topic-to-conversation
 *     promotion
 *   - is attributed to a `participantId` (who originated or owns it)
 *   - carries a lifecycle `state` (Active while in flight, Complete when terminal)
 *   - is persisted in RocksDB; may be mutated in-place while Active via
 *     [[sigil.signal.Delta]]s, at which point the updated state is written back
 *
 * Registration for wire polymorphism happens via [[sigil.signal.Signal]], not
 * here — Event is a category marker within the broader Signal discriminator.
 */
trait Event extends Signal with Document[Event] {
  def participantId: ParticipantId
  def conversationId: Id[Conversation]
  def topicId: Id[Topic]
  def timestamp: Timestamp
  def state: EventState

  /**
   * Conversational role this event plays in the agent ↔ provider
   * exchange. `Role.Tool` marks the event as a tool's result —
   * always re-triggers the agent's self-loop and renders as
   * `role: "tool"` on the wire. `Role.Standard` (default for most
   * subclasses) is everything else.
   *
   * Each concrete Event subclass declares this as a constructor
   * field so callers can pick the role at emission time (e.g. a
   * tool that wants its `Message` to be a tool result emits
   * `Message(content = ..., role = Role.Tool)`). Subclasses whose
   * role is invariant (e.g. `ToolResults`) default the field to
   * the appropriate value.
   */
  def role: Role

  /**
   * Returns a copy of this event with its `state` replaced. Used by
   * [[sigil.signal.StateDelta]] to drive the universal Active → Complete
   * transition. Each concrete Event implements this by delegating to its
   * own `copy(state = state)`.
   */
  def withState(state: EventState): Event
}

object Event extends JsonConversion[Event] {
  import Signal.given

  implicit override def rw: RW[Event] = summon[RW[Signal]].asInstanceOf[RW[Event]]
}
