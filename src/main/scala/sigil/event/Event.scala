package sigil.event

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
trait Event extends Signal {
  def id: Id[Event]
  def participantId: ParticipantId
  def conversationId: Id[Conversation]
  def state: EventState
  def visibility: Set[EventVisibility]
  def timestamp: Timestamp
}

object Event {

  /**
   * Generate a new event ID.
   */
  def id(): Id[Event] = Id[Event]()
}
