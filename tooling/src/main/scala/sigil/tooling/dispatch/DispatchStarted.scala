package sigil.tooling.dispatch

import fabric.rw.*
import lightdb.id.Id
import lightdb.time.Timestamp
import sigil.conversation.{Conversation, Topic}
import sigil.event.{Event, MessageRole, MessageVisibility}
import sigil.participant.ParticipantId
import sigil.signal.EventState

/**
 * Fired into the parent conversation when [[DispatchWorkersTool]]
 * kicks off a fanout (sigil #288). Carries the total scope so
 * embedding apps can render an aggregate progress chip
 * ("0/100 done, 5 running") from second one — without waiting for
 * the first worker to settle.
 *
 *   - `dispatchId` — opaque session id; echoed on
 *     [[DispatchCompleted]].
 *   - `total` — total items the dispatch will process.
 *   - `workersStarted` — initial batch size: `min(total, maxParallel)`.
 *   - `maxParallel` — concurrency cap; the queue advances as
 *     workers settle.
 */
case class DispatchStarted(participantId: ParticipantId,
                           conversationId: Id[Conversation],
                           topicId: Id[Topic],
                           topicIndex: Int = 0,
                           dispatchId: String,
                           total: Int,
                           workersStarted: Int,
                           maxParallel: Int,
                           override val state: EventState = EventState.Complete,
                           override val role: MessageRole = MessageRole.Standard,
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
