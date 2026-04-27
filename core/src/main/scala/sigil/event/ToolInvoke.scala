package sigil.event

import fabric.rw.*
import lightdb.id.Id
import lightdb.time.Timestamp
import lightdb.util.Nowish
import sigil.conversation.{Conversation, Topic}
import sigil.participant.ParticipantId
import sigil.signal.EventState
import sigil.tool.{ToolInput, ToolName}

/**
 * Records that a tool call happened. Created `Active` at `ToolCallStart` by
 * the orchestrator and transitioned to `Complete` at `ToolCallComplete` —
 * regardless of whether the tool is streaming-producing (like `respond`) or
 * atomic (like `change_mode`). Gives subscribers a consistent "tool is
 * running / tool finished" lifecycle for every call.
 *
 * `input` is `None` while the call is in flight (the LLM is still streaming
 * args) and populated via a [[sigil.signal.ToolDelta]] when the call
 * completes.
 */
case class ToolInvoke(toolName: ToolName,
                      participantId: ParticipantId,
                      conversationId: Id[Conversation],
                      topicId: Id[Topic],
                      input: Option[ToolInput] = None,
                      state: EventState = EventState.Active,
                      timestamp: Timestamp = Timestamp(Nowish()),
                      role: MessageRole = MessageRole.Standard,
                      _id: Id[Event] = Event.id())
  extends Event derives RW {
  override def withState(state: EventState): Event = copy(state = state)
}
