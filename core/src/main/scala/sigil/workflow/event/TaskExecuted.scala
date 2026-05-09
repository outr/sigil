package sigil.workflow.event

import fabric.rw.*
import lightdb.id.Id
import lightdb.time.Timestamp
import sigil.conversation.{Conversation, Topic}
import sigil.event.{Event, MessageRole, MessageVisibility}
import sigil.participant.ParticipantId
import sigil.signal.EventState

/**
 * Worker-shaped sibling of [[WorkflowRunCompleted]]. Emitted into
 * the worker's *parent* conversation (the user-facing one that
 * originally called `delegate_task`) when a worker run settles —
 * gives the parent agent a typed signal carrying the worker's
 * `summary`, the originating `role` name, and the iteration count
 * without having to introspect the underlying step results.
 *
 * The standard `WorkflowRunCompleted` Event still fires into the
 * worker's own scratchpad conversation. `TaskExecuted` is the
 * cross-conversation echo that lifts the result up to the parent
 * agent's surface for it to summarize, surface to the user, or
 * trigger follow-on action.
 *
 * `summary` is the post-`Complete:` text the worker emitted (or
 * the truncated last response when the worker hit the
 * `maxIterations` runaway cap, in which case `exhausted = true`).
 * `iterations` reflects how many AgentDecisionStep iterations
 * actually fired.
 */
case class TaskExecuted(participantId: ParticipantId,
                        conversationId: Id[Conversation],
                        topicId: Id[Topic],
                        topicIndex: Int = 0,
                        taskId: String,
                        roleName: String,
                        summary: String,
                        iterations: Int,
                        exhausted: Boolean = false,
                        workerConversationId: Option[Id[Conversation]] = None,
                        override val state: EventState = EventState.Complete,
                        override val role: MessageRole = MessageRole.Standard,
                        override val visibility: MessageVisibility = MessageVisibility.All,
                        timestamp: Timestamp = Timestamp(),
                        override val origin: Option[Id[Event]] = None,
                        override val source: Option[String] = None,
                        override val contextFrame: Option[sigil.conversation.ContextFrame] = None,
                        _id: Id[Event] = Event.id()) extends sigil.event.ControlPlaneEvent derives RW {
  override def withState(state: EventState): Event = copy(state = state)
  override def withOrigin(origin: Option[Id[Event]]): Event = copy(origin = origin)
  override def withContextFrame(contextFrame: Option[sigil.conversation.ContextFrame]): Event = copy(contextFrame = contextFrame)
  override def withConversationId(conversationId: lightdb.id.Id[sigil.conversation.Conversation]): Event = copy(conversationId = conversationId)
}
