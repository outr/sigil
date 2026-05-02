package sigil.event

import fabric.rw.*
import lightdb.id.Id
import lightdb.time.Timestamp
import lightdb.util.Nowish
import sigil.conversation.{Conversation, Topic}
import sigil.participant.ParticipantId
import sigil.provider.TokenUsage
import sigil.signal.EventState
import sigil.spatial.Place
import sigil.tool.model.ResponseContent

/**
 * A message from a participant — user input, agent output, or system message.
 * The participantId identifies who sent it; the content carries structured blocks.
 *
 * Created `Active` by streaming-producing tools (content populated via
 * `MessageDelta`); transitions to `Complete` when the producer signals end of
 * stream. Atomic Messages — e.g. a user typing a one-shot message or a
 * `FindCapabilityTool` result — are created directly as `Complete`.
 *
 * `location` carries a [[Place]] describing where the message originated.
 * Apps typically capture the raw GPS point at the client; the framework's
 * `locationFor` hook can also populate `Place(point, None, None)` in the
 * publish pipeline for non-agent senders. When a non-NoOp
 * `Geocoder` is wired, an async enrichment task resolves the point to a
 * named Place (name/address) and applies the result via
 * [[sigil.signal.LocationDelta]].
 *
 * Privacy: the framework treats `location` as sender-private. Read paths
 * that surface Messages to another viewer MUST call
 * `Sigil.applyViewerTransforms(signal, viewerId)` (or subscribe to
 * `Sigil.signalsFor(viewer)`) — the default
 * `RedactLocationTransform` strips `location` for non-senders.
 * Projection-level reads are safe by construction: `ContextFrame`
 * carries no geo field.
 *
 * `location` is kept on `Message` rather than on the `Event` trait because
 * admin/system events (`TitleUpdated`, `Deleted`, `ErrorOccurred`) have
 * weak-to-meaningless geo semantics. If more event subtypes need geo
 * later, promote via a `Geolocated` mixin rather than widening `Event`.
 */
case class Message(participantId: ParticipantId,
                   conversationId: Id[Conversation],
                   topicId: Id[Topic],
                   content: Vector[ResponseContent] = Vector.empty,
                   usage: TokenUsage = TokenUsage(0, 0, 0),
                   state: EventState = EventState.Active,
                   timestamp: Timestamp = Timestamp(Nowish()),
                   location: Option[Place] = None,
                   role: MessageRole = MessageRole.Standard,
                   override val visibility: MessageVisibility = MessageVisibility.All,
                   override val origin: Option[Id[Event]] = None,
                   _id: Id[Event] = Event.id())
  extends Event derives RW {
  override def withState(state: EventState): Event = copy(state = state)
  override def withOrigin(origin: Option[Id[Event]]): Event = copy(origin = origin)
}
