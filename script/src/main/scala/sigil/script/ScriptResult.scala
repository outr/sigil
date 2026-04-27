package sigil.script

import fabric.rw.*
import lightdb.id.Id
import lightdb.time.Timestamp
import lightdb.util.Nowish
import sigil.conversation.{Conversation, Topic}
import sigil.event.{Event, MessageVisibility, MessageRole}
import sigil.participant.ParticipantId
import sigil.signal.EventState

/**
 * Result of an [[ExecuteScriptTool]] invocation. Default
 * `role = MessageRole.Tool` re-triggers the agent's loop with this payload
 * surfaced as a tool result; default
 * `visibility = MessageVisibility.Agents` keeps script output out of
 * the user-facing wire stream (apps that want to surface script
 * results to the user override at emission time).
 *
 * `output` is the executor's stringified return value;
 * `error` is set instead when the script threw.
 */
case class ScriptResult(participantId: ParticipantId,
                        conversationId: Id[Conversation],
                        topicId: Id[Topic],
                        output: Option[String] = None,
                        error: Option[String] = None,
                        durationMs: Long = 0L,
                        timestamp: Timestamp = Timestamp(Nowish()),
                        state: EventState = EventState.Complete,
                        override val role: MessageRole = MessageRole.Tool,
                        override val visibility: MessageVisibility = MessageVisibility.Agents,
                        _id: Id[Event] = Event.id())
  extends Event derives RW {
  override def withState(state: EventState): Event = copy(state = state)
}
