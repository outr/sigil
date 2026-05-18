package sigil.workflow.event

import fabric.rw.*
import lightdb.id.Id
import lightdb.time.Timestamp
import sigil.conversation.Conversation
import sigil.conversation.Topic
import sigil.event.{Event, MessageRole, MessageVisibility}
import sigil.participant.ParticipantId
import sigil.signal.EventState

/**
 * Sigil [[Event]] surfaced into the originating conversation when a
 * workflow run transitions from scheduled to running. Only emitted
 * when the workflow's `conversationId` is set — cron-scheduled
 * runs without a conversation context produce no Event.
 */
case class WorkflowRunStarted(participantId: ParticipantId,
                              conversationId: Id[Conversation],
                              topicId: Id[Topic],
                              topicIndex: Int = 0,
                              workflowId: String,
                              workflowName: String,
                              runId: String,
                              override val state: EventState = EventState.Complete,
                              override val role: MessageRole = MessageRole.Standard,
                              override val visibility: MessageVisibility = MessageVisibility.All,
                              timestamp: Timestamp = Timestamp(),
                              override val origin: Option[Id[Event]] = None,
                              override val source: Option[String] = None,
                              override val contextFrame: Option[sigil.conversation.ContextFrame] = None,
                              _id: Id[Event] = Event.id())
  extends sigil.event.ControlPlaneEvent derives RW {
  override def withState(state: EventState): Event = copy(state = state)
  override def withOrigin(origin: Option[Id[Event]]): Event = copy(origin = origin)
  override def withContextFrame(contextFrame: Option[sigil.conversation.ContextFrame]): Event = copy(contextFrame = contextFrame)
  override def withConversationId(conversationId: lightdb.id.Id[sigil.conversation.Conversation]): Event =
    copy(conversationId = conversationId)
}
