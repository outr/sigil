package sigil.event

import fabric.rw.*
import lightdb.id.Id
import lightdb.time.Timestamp
import lightdb.util.Nowish
import sigil.conversation.{Conversation, Topic}
import sigil.participant.ParticipantId
import sigil.signal.EventState
import sigil.tool.discovery.CapabilityMatch

case class CapabilityResults(matches: List[CapabilityMatch],
                             participantId: ParticipantId,
                             conversationId: Id[Conversation],
                             topicId: Id[Topic],
                             /**
                              * Normalised query keywords that produced these
                              * matches. Empty when not populated by the framework's
                              * [[sigil.tool.core.FindCapabilityTool]] (external
                              * callers, replayed history).
                              */
                             query: String = "",
                             topicIndex: Int = 0,
                             state: EventState = EventState.Active,
                             timestamp: Timestamp = Timestamp(Nowish()),
                             role: MessageRole = MessageRole.Tool,
                             override val origin: Option[Id[Event]] = None,
                             override val source: Option[String] = None,
                             override val contextFrame: Option[sigil.conversation.ContextFrame] = None,
                             _id: Id[Event] = Event.id())
  extends Event derives RW {
  override def withState(state: EventState): Event = copy(state = state)
  override def withTopic(topicId: Id[Topic], topicIndex: Int): Event = copy(topicId = topicId, topicIndex = topicIndex)
  override def withOrigin(origin: Option[Id[Event]]): Event = copy(origin = origin)
  override def withContextFrame(contextFrame: Option[sigil.conversation.ContextFrame]): Event = copy(contextFrame = contextFrame)
  override def withConversationId(conversationId: Id[sigil.conversation.Conversation]): Event = copy(conversationId = conversationId)
}
