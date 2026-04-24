package sigil.pipeline

import rapid.Task
import sigil.Sigil
import sigil.event.Message
import sigil.participant.AgentParticipantId
import sigil.signal.Signal

/**
 * Default [[InboundTransform]] that fills [[Message.location]] via
 * [[Sigil.locationFor]] for non-agent-authored Messages whose location
 * is empty. Agent-authored Messages and Messages that already carry a
 * `Place` pass through unchanged.
 */
object LocationCaptureTransform extends InboundTransform {
  override def apply(signal: Signal, self: Sigil): Task[Signal] = signal match {
    case m: Message if m.location.isEmpty && !m.participantId.isInstanceOf[AgentParticipantId] =>
      self.locationFor(m.participantId, m.conversationId).map {
        case Some(place) => m.copy(location = Some(place))
        case None => m
      }
    case other => Task.pure(other)
  }
}
