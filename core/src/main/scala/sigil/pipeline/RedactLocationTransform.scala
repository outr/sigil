package sigil.pipeline

import sigil.Sigil
import sigil.event.Message
import sigil.participant.ParticipantId
import sigil.signal.Signal

/**
 * Default [[ViewerTransform]] that strips `Message.location` for
 * viewers other than the sender. The framework's privacy contract
 * for geospatial data: precise Places are sender-private.
 */
object RedactLocationTransform extends ViewerTransform {
  override def apply(signal: Signal, viewer: ParticipantId, self: Sigil): Signal = signal match {
    case m: Message if m.participantId != viewer => m.copy(location = None)
    case other => other
  }
}
