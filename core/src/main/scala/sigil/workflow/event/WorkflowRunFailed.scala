package sigil.workflow.event

import fabric.rw.*
import lightdb.id.Id
import lightdb.time.Timestamp
import sigil.conversation.{Conversation, Topic}
import sigil.event.{Event, MessageRole, MessageVisibility}
import sigil.participant.ParticipantId
import sigil.signal.EventState

/**
 * Sigil [[Event]] emitted into the originating conversation when a
 * workflow run fails. `reason` carries a short summary; the
 * workflow's `WorkflowHistory` carries the full detail.
 */
case class WorkflowRunFailed(participantId: ParticipantId,
                             conversationId: Id[Conversation],
                             topicId: Id[Topic],
                             topicIndex: Int = 0,
                             workflowId: String,
                             workflowName: String,
                             runId: String,
                             reason: String,
                             // Sigil #381 — the scheduling (bound) conversation (see WorkflowRunStarted).
                             parentConversationId: Option[Id[Conversation]] = None,
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
  override def withTopic(topicId: Id[Topic], topicIndex: Int): Event = copy(topicId = topicId, topicIndex = topicIndex)
  override def withOrigin(origin: Option[Id[Event]]): Event = copy(origin = origin)
  override def withContextFrame(contextFrame: Option[sigil.conversation.ContextFrame]): Event = copy(contextFrame = contextFrame)
  override def withConversationId(conversationId: lightdb.id.Id[sigil.conversation.Conversation]): Event =
    copy(conversationId = conversationId)

  /**
   * Deliver this run-lifecycle Event to the bound parent's subscribers too,
   * so the parent's activity bar surfaces a run that lives on its own
   * sub-conversation (#376/#385).
   */
  override def additionalDeliveryScopes: Set[Id[Conversation]] = parentConversationId.toSet
}
