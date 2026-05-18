package sigil.signal

import fabric.rw.*
import lightdb.id.Id
import lightdb.time.Timestamp
import sigil.conversation.Conversation
import sigil.event.{Event, ReadState}
import sigil.participant.ParticipantId

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
 * **`participantId`** is carried on the delta itself (bug #66)
 * so consumers can route the cursor advance per-participant
 * without re-resolving the parent `ReadState`. Matches the
 * pattern other event/delta pairs use (e.g. `Message` /
 * `MessageDelta` carry `target`, `Reaction` carries
 * participantId on the event itself). The `target` id encodes
 * `(conversationId, participantId)` per
 * [[ReadState.idFor]], but exposing the participant explicitly
 * lets consumers route without parsing the id.
 *
 * Non-target events are passed through unchanged — the typed
 * target guards application.
 */
case class ReadStateDelta(target: Id[Event],
                          conversationId: Id[Conversation],
                          participantId: ParticipantId,
                          lastReadAt: Timestamp)
  extends Delta derives RW {

  override def apply(target: Event): Event = target match {
    case r: ReadState => r.copy(lastReadAt = lastReadAt)
    case other => other
  }
}
