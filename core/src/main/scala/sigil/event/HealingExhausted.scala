package sigil.event

import fabric.rw.*
import lightdb.id.Id
import lightdb.time.Timestamp
import lightdb.util.Nowish
import sigil.conversation.{Conversation, Topic}
import sigil.participant.ParticipantId
import sigil.signal.EventState

/**
 * Durable audit pulse — emitted when the retry that followed a
 * successful heal ALSO failed. Names which strategy ran and captures
 * the retry's error verbatim. From here the agent loop's standard
 * failure path runs (Failure Message + claim release); the framework
 * does NOT heal again on this turn.
 *
 * Pairs with the preceding [[ConversationCorruptionDetected]] /
 * [[ConversationHealed]] via `correlationId`.
 */
case class HealingExhausted(conversationId: Id[Conversation],
                            topicId: Id[Topic],
                            correlationId: String,
                            strategyName: String,
                            retryError: ErrorEvidence,
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
  override def withTopic(topicId: Id[Topic], topicIndex: Int): Event = copy(topicId = topicId, topicIndex = topicIndex)
  override def withOrigin(origin: Option[Id[Event]]): Event = copy(origin = origin)
  override def withContextFrame(contextFrame: Option[sigil.conversation.ContextFrame]): Event = copy(contextFrame = contextFrame)
  override def withConversationId(conversationId: Id[sigil.conversation.Conversation]): Event = copy(conversationId = conversationId)
}
