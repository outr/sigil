package sigil.tooling.dispatch

import fabric.rw.*
import lightdb.id.Id
import lightdb.time.Timestamp
import sigil.conversation.{Conversation, Topic}
import sigil.event.{Event, MessageRole, MessageVisibility}
import sigil.participant.ParticipantId
import sigil.signal.EventState

/**
 * Fired into the parent conversation when every worker in a
 * [[DispatchWorkersTool]] fanout has settled (sigil #288).
 *
 * Acts as the trigger for the parent agent's next iteration —
 * its `MessageRole.Tool` + `MessageVisibility.Agents` shape
 * matches the usual "tool result re-fires the agent loop"
 * pattern. Parent agent reads `workers` to learn per-worker
 * status and `summary`, drills into specific workers via
 * cross-conversation reads (see [[sigil.Sigil.canReadConversation]])
 * when more detail is needed.
 *
 *   - `dispatchId` — opaque session id; matches the originating
 *     [[DispatchStarted]] and the immediate `DispatchWorkersOutput`
 *     handle.
 *   - `total` — total items dispatched.
 *   - `succeeded` / `failed` — per-worker terminal status counts.
 *   - `workers` — one [[WorkerSummary]] per dispatched worker, in
 *     item-index order.
 */
case class DispatchCompleted(participantId: ParticipantId,
                             conversationId: Id[Conversation],
                             topicId: Id[Topic],
                             topicIndex: Int = 0,
                             dispatchId: String,
                             total: Int,
                             succeeded: Int,
                             failed: Int,
                             workers: List[WorkerSummary],
                             override val state: EventState = EventState.Complete,
                             override val role: MessageRole = MessageRole.Tool,
                             override val visibility: MessageVisibility = MessageVisibility.Agents,
                             timestamp: Timestamp = Timestamp(),
                             override val origin: Option[Id[Event]] = None,
                             override val source: Option[String] = None,
                             override val contextFrame: Option[sigil.conversation.ContextFrame] = None,
                             _id: Id[Event] = Event.id()) extends sigil.event.ControlPlaneEvent derives RW {
  override def withState(state: EventState): Event = copy(state = state)
  override def withOrigin(origin: Option[Id[Event]]): Event = copy(origin = origin)
  override def withContextFrame(contextFrame: Option[sigil.conversation.ContextFrame]): Event = copy(contextFrame = contextFrame)
  override def withConversationId(conversationId: Id[Conversation]): Event = copy(conversationId = conversationId)
}
