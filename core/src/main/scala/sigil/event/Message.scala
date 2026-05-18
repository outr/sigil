package sigil.event

import fabric.rw.*
import lightdb.id.Id
import lightdb.time.Timestamp
import lightdb.util.Nowish
import sigil.conversation.{Conversation, Topic}
import sigil.db.Model
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
 *
 * `modelId` records the model that produced this Message — set on Messages
 * authored by a provider call (the orchestrator stamps it from the
 * resolved `ConversationRequest.modelId`, which already accounts for any
 * `ProviderStrategy.routed` candidate selection). Left `None` on
 * Messages the framework synthesizes (user input, tool results,
 * diagnostics, image-only outputs without a token cost). Drives the
 * per-conversation `Conversation.cost` projection.
 *
 * `modelDisplayName` is the friendly UI label corresponding to
 * `modelId`, stamped at the same emission point from the model
 * registry's [[sigil.db.Model.displayName]]. Wire convenience so
 * clients don't have to materialize a model cache to render
 * `"GPT-5.5"` rather than `"openai/gpt-5.5"`. `None` when `modelId`
 * is unset or when the registry entry has no displayName populated.
 */
case class Message(participantId: ParticipantId,
                   conversationId: Id[Conversation],
                   topicId: Id[Topic],
                   topicIndex: Int = 0,
                   content: Vector[ResponseContent] = Vector.empty,
                   usage: TokenUsage = TokenUsage(0, 0, 0),
                   modelId: Option[Id[Model]] = None,
                   modelDisplayName: Option[String] = None,
                   state: EventState = EventState.Active,
                   timestamp: Timestamp = Timestamp(Nowish()),
                   location: Option[Place] = None,
                   role: MessageRole = MessageRole.Standard,
                   /**
                    * Was this Message a normal reply or a Failure
                    * signal? Default Success. Set by `RespondTool` from
                    * the agent's `disposition` input; set by the
                    * framework's exception-wrapping path when a tool
                    * dispatch throws. Content carries the reason.
                    */
                   disposition: MessageDisposition = MessageDisposition.Success,
                   optionSelection: Option[OptionSelection] = None,
                   override val visibility: MessageVisibility = MessageVisibility.All,
                   override val origin: Option[Id[Event]] = None,
                   override val source: Option[String] = None,
                   override val contextFrame: Option[sigil.conversation.ContextFrame] = None,
                   _id: Id[Event] = Event.id())
  extends Event derives RW {
  override def withState(state: EventState): Event = copy(state = state)
  override def withOrigin(origin: Option[Id[Event]]): Event = copy(origin = origin)
  override def withContextFrame(contextFrame: Option[sigil.conversation.ContextFrame]): Event = copy(contextFrame = contextFrame)
  override def withConversationId(conversationId: Id[Conversation]): Event = copy(conversationId = conversationId)

  /**
   * True when this Message represents a Failure-disposition reply.
   */
  def isFailure: Boolean = disposition.isInstanceOf[MessageDisposition.Failure]

  /**
   * True when this Message represents a Success-disposition reply.
   */
  def isSuccess: Boolean = disposition == MessageDisposition.Success

  /**
   * The Failure-disposition reason (concatenated text blocks) when
   * this Message is a Failure. None for Success.
   */
  def failureReason: Option[String] =
    if (isFailure) {
      val text = content.collect {
        case ResponseContent.Text(t) => t
        case ResponseContent.Markdown(t) => t
      }.mkString("\n").trim
      Option.when(text.nonEmpty)(text)
    } else None
}
