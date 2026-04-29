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
 * workflow step finishes. `success = false` means the step failed —
 * the wrapping run may still continue if the step was marked
 * `continueOnError`.
 */
case class WorkflowStepCompleted(participantId: ParticipantId,
                                 conversationId: Id[Conversation],
                                 topicId: Id[Topic],
                                 workflowId: String,
                                 runId: String,
                                 stepId: String,
                                 stepName: String,
                                 success: Boolean,
                                 override val state: EventState = EventState.Complete,
                                 override val role: MessageRole = MessageRole.Standard,
                                 override val visibility: MessageVisibility = MessageVisibility.All,
                                 timestamp: Timestamp = Timestamp(),
                                 _id: Id[Event] = Event.id()) extends sigil.event.ControlPlaneEvent derives RW {
  override def withState(state: EventState): Event = copy(state = state)
}
