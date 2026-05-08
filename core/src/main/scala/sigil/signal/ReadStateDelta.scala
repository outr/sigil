package sigil.signal

import fabric.rw.*
import lightdb.id.Id
import lightdb.time.Timestamp
import sigil.conversation.Conversation
import sigil.event.{Event, ReadState}

/**
 * Transient update to a [[sigil.event.ReadState]] event. Carries
 * the new `lastReadAt` cursor; the underlying event row is
 * mutated in place. Bug #62.
 *
 * High-frequency by design — every scroll-to-bottom (or
 * equivalent client signal) emits one. The Delta path doesn't
 * grow `db.events` (it upserts the existing row), so the wire
 * cost is one Signal broadcast per advance + one indexed write
 * to a single row.
 *
 * Non-target events are passed through unchanged — the typed
 * target guards application.
 */
case class ReadStateDelta(target: Id[Event],
                          conversationId: Id[Conversation],
                          lastReadAt: Timestamp)
  extends Delta derives RW {

  override def apply(target: Event): Event = target match {
    case r: ReadState => r.copy(lastReadAt = lastReadAt)
    case other        => other
  }
}
