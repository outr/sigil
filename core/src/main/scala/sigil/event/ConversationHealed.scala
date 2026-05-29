package sigil.event

import fabric.rw.*
import lightdb.id.Id
import lightdb.time.Timestamp
import lightdb.util.Nowish
import sigil.conversation.{Conversation, Topic}
import sigil.heal.HealAction
import sigil.participant.ParticipantId
import sigil.signal.EventState

/**
 * Durable audit pulse — emitted after a [[sigil.heal.HealingStrategy]]
 * has applied its corrections to the conversation's durable log and
 * BEFORE the framework retries the agent iteration. Pairs with the
 * preceding [[ConversationCorruptionDetected]] via
 * `correlationId`.
 *
 * `corrections` carries each [[HealAction]] the strategy returned —
 * the synthesised event ids are pointable from log aggregators back
 * at the durable rows. `remainingIssues` is the strategy's free-form
 * list of follow-up shapes the heal could not fix; surfacing it
 * here keeps partial fixes visible rather than hidden.
 */
case class ConversationHealed(conversationId: Id[Conversation],
                              topicId: Id[Topic],
                              correlationId: String,
                              strategyName: String,
                              corrections: List[HealAction],
                              remainingIssues: List[String],
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
