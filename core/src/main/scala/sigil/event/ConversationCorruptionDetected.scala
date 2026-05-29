package sigil.event

import fabric.rw.*
import lightdb.id.Id
import lightdb.time.Timestamp
import lightdb.util.Nowish
import sigil.conversation.{Conversation, Topic}
import sigil.db.Model
import sigil.heal.CorruptionEvidence
import sigil.participant.ParticipantId
import sigil.signal.EventState

/**
 * Durable audit pulse — emitted at the agent loop's `handleError`
 * boundary when an error matches a registered
 * [[sigil.heal.HealingStrategy]]. Lands BEFORE the strategy runs (in
 * [[sigil.heal.HealingMode.Recover]]) and BEFORE the original error
 * is re-thrown (in [[sigil.heal.HealingMode.Strict]]) so the failure
 * is recorded either way.
 *
 *  `detectorSource` discriminates where the corruption surfaced:
 *  - `"provider-call"` — the upstream HTTP 400'd with a
 *    "No tool output found for function call …" body (or equivalent
 *    Anthropic / Google phrasing).
 *  - `"renderFrames-invariant"` — the framework's `renderFrames`
 *    threw [[sigil.heal.BrokenHistoryException]] mid-render.
 *
 *  Apps with their own detector sources stamp their own opaque
 *  strings — the framework treats the field as opaque.
 */
case class ConversationCorruptionDetected(conversationId: Id[Conversation],
                                          topicId: Id[Topic],
                                          detectorSource: String,
                                          originalError: ErrorEvidence,
                                          correlationId: String,
                                          modelId: Option[Id[Model]],
                                          detectedCorruption: List[CorruptionEvidence],
                                          participantId: ParticipantId,
                                          topicIndex: Int = 0,
                                          state: EventState = EventState.Complete,
                                          timestamp: Timestamp = Timestamp(Nowish()),
                                          role: MessageRole = MessageRole.Standard,
                                          override val origin: Option[Id[Event]] = None,
                                          override val source: Option[String] = Some("framework-heal"),
                                          override val contextFrame: Option[sigil.conversation.ContextFrame] = None,
                                          _id: Id[Event] = Event.id())
  extends Event derives RW {
  override def withState(state: EventState): Event = copy(state = state)
  override def withOrigin(origin: Option[Id[Event]]): Event = copy(origin = origin)
  override def withContextFrame(contextFrame: Option[sigil.conversation.ContextFrame]): Event = copy(contextFrame = contextFrame)
  override def withConversationId(conversationId: Id[sigil.conversation.Conversation]): Event = copy(conversationId = conversationId)
}
