package sigil.event

import fabric.rw.*
import lightdb.id.Id
import lightdb.time.Timestamp
import lightdb.util.Nowish
import sigil.conversation.{Conversation, Topic}
import sigil.participant.ParticipantId
import sigil.signal.EventState

/**
 * Periodic agent self-reflection checkpoint emitted at every Nth
 * iteration of the agent loop. Each checkpoint anchors to the
 * prior checkpoint's status as a comparison baseline so the
 * framework can detect "I've been doing the same thing" loops
 * the agent itself may not recognise.
 *
 *   - `prevCheckpointStatus` is the prior checkpoint's
 *     `currentStatus` (or None for the first checkpoint).
 *   - `meaningfulProgress = false` for two consecutive checkpoints
 *     trips the stuck-detection path and the framework emits a
 *     synthetic respond asking the user for guidance.
 *   - `shouldAskUser = true` ends the turn immediately with a
 *     respond carrying the agent's stated need.
 *
 * The chain of checkpoint events forms a natural timeline a UI
 * can render alongside the conversation — the user sees where
 * the agent has been without reading every individual tool
 * call.
 */
case class ProgressCheckpoint(participantId: ParticipantId,
                              conversationId: Id[Conversation],
                              topicId: Id[Topic],
                              iterationCount: Int,
                              prevCheckpointStatus: Option[String],
                              currentStatus: String,
                              meaningfulProgress: Boolean,
                              remainingSteps: String,
                              stuckOn: Option[String],
                              shouldAskUser: Boolean,
                              topicIndex: Int = 0,
                              state: EventState = EventState.Complete,
                              timestamp: Timestamp = Timestamp(Nowish()),
                              role: MessageRole = MessageRole.Standard,
                              override val origin: Option[Id[Event]] = None,
                              override val source: Option[String] = None,
                              override val contextFrame: Option[sigil.conversation.ContextFrame] = None,
                              _id: Id[Event] = Event.id())
  extends ControlPlaneEvent derives RW {
  override def withState(state: EventState): Event = copy(state = state)
  override def withOrigin(origin: Option[Id[Event]]): Event = copy(origin = origin)
  override def withContextFrame(contextFrame: Option[sigil.conversation.ContextFrame]): Event = copy(contextFrame = contextFrame)
  override def withConversationId(conversationId: Id[Conversation]): Event = copy(conversationId = conversationId)
}
