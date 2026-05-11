package sigil.event

import fabric.rw.*
import lightdb.id.Id
import lightdb.time.Timestamp
import lightdb.util.Nowish
import sigil.conversation.{Conversation, Topic}
import sigil.db.Model
import sigil.participant.ParticipantId
import sigil.provider.{Complexity, WorkType}
import sigil.signal.EventState

/** Per-turn routing-decision event. Exposes the framework's
  * candidate-selection inputs and outcome so wire-log forensics can
  * answer "why did this turn go to model X?".
  *
  *   - `inferredWorkType` / `inferredComplexity` — `Some` when a
  *     classifier actually ran, `None` when the framework defaulted
  *     to `mode.workType` / `Complexity.Medium` because the
  *     strategy's skip gates trivialised the decision.
  *   - `candidateChain` — the ordered list of model ids the routing
  *     strategy returned for the resolved `WorkType`.
  *   - `chosenModelId` — the candidate that won.
  *   - `skipReasons` — for each candidate the strategy ranked below
  *     `chosenModelId` (or above it but skipped for capability /
  *     cooldown reasons), the reason for the skip.
  *   - `classifierLatencyMs` — wall-clock cost of the classifier
  *     consult, when one ran.
  *   - `escalationCount` — how many `request_escalation` bumps have
  *     been applied to this user turn.
  */
case class RouteResolved(participantId: ParticipantId,
                         conversationId: Id[Conversation],
                         topicId: Id[Topic],
                         userMessageId: Option[Id[Event]],
                         inferredWorkType: Option[WorkType],
                         inferredComplexity: Option[Complexity],
                         candidateChain: List[Id[Model]],
                         chosenModelId: Id[Model],
                         skipReasons: Map[Id[Model], String],
                         classifierLatencyMs: Option[Long],
                         escalationCount: Int,
                         topicIndex: Int = 0,
                         state: EventState = EventState.Complete,
                         timestamp: Timestamp = Timestamp(Nowish()),
                         role: MessageRole = MessageRole.Standard,
                         override val visibility: MessageVisibility = MessageVisibility.Agents,
                         override val origin: Option[Id[Event]] = None,
                         override val source: Option[String] = None,
                         override val contextFrame: Option[sigil.conversation.ContextFrame] = None,
                         _id: Id[Event] = Event.id())
  extends Event with ControlPlaneEvent derives RW {
  override def withState(state: EventState): Event = copy(state = state)
  override def withOrigin(origin: Option[Id[Event]]): Event = copy(origin = origin)
  override def withContextFrame(contextFrame: Option[sigil.conversation.ContextFrame]): Event =
    copy(contextFrame = contextFrame)
  override def withConversationId(conversationId: Id[Conversation]): Event =
    copy(conversationId = conversationId)
}
