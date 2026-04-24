package sigil.pipeline

import rapid.Task
import sigil.Sigil
import sigil.event.Message
import sigil.participant.AgentParticipantId
import sigil.signal.{LocationDelta, Signal}
import sigil.spatial.NoOpGeocoder

/**
 * Default [[SettledEffect]] that resolves a bare GPS point on a
 * non-agent-authored [[Message]] to a named [[sigil.spatial.Place]].
 *
 * Runs fire-and-forget: the effect spawns a background fiber that
 * calls the configured [[Sigil.geocoder]] and, on success, publishes
 * a [[LocationDelta]] to update the persisted Message in place. The
 * effect's returned Task completes immediately so publish latency is
 * not coupled to geocoding latency.
 *
 * Short-circuits when the geocoder is [[NoOpGeocoder]], the signal
 * isn't a Message, the participant is an agent, or the Message's
 * Place already has enrichment metadata (`name` or `address`).
 */
object GeocodingEnrichmentEffect extends SettledEffect {
  override def apply(signal: Signal, self: Sigil): Task[Unit] = signal match {
    case m: Message if shouldEnrich(m, self) =>
      val point = m.location.get.point
      val work = self.geocoder.geocode(point).flatMap {
        case Some(result) =>
          self.publish(LocationDelta(
            target = m._id,
            conversationId = m.conversationId,
            location = result.place
          ))
        case None => Task.unit
      }.handleError { t =>
        Task(scribe.warn(s"Geocoding failed for message ${m._id.value}", t))
      }
      Task(work.startUnit()).unit
    case _ => Task.unit
  }

  private def shouldEnrich(m: Message, self: Sigil): Boolean = {
    if (self.geocoder eq NoOpGeocoder) false
    else if (m.participantId.isInstanceOf[AgentParticipantId]) false
    else m.location.exists(p => p.name.isEmpty && p.address.isEmpty)
  }
}
