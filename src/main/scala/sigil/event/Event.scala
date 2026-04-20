package sigil.event

import fabric.rw.RW
import lightdb.doc.{Document, JsonConversion}
import lightdb.id.Id
import lightdb.time.Timestamp
import sigil.conversation.Conversation
import sigil.participant.ParticipantId
import sigil.signal.{EventState, Signal}

/**
 * A durable, stateful record in the conversation log. Every Event:
 *
 *   - belongs to a single `conversationId`
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
  def timestamp: Timestamp
  def state: EventState
  def visibility: Set[EventVisibility]

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

  override implicit def rw: RW[Event] = summon[RW[Signal]].asInstanceOf[RW[Event]]
}
