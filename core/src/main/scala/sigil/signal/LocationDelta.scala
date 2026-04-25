package sigil.signal

import fabric.rw.*
import lightdb.id.Id
import sigil.conversation.Conversation
import sigil.event.{Event, Message}
import sigil.spatial.Place

/**
 * Applies an enriched [[Place]] to an already-persisted [[Message]]. Emitted
 * by the framework's async geocoding pipeline after a user-authored Message
 * has been captured with a raw point and the configured
 * [[sigil.spatial.Geocoder]] has resolved it to a named Place.
 *
 * Applying the delta replaces the Message's `location` wholesale — callers
 * that want partial updates (e.g. enrich address only) should emit a new
 * `LocationDelta` carrying the full Place.
 */
case class LocationDelta(target: Id[Event],
                         conversationId: Id[Conversation],
                         location: Place)
  extends Delta derives RW {

  override def apply(target: Event): Event = target match {
    case m: Message => m.copy(location = Some(location))
    case other => other
  }
}
