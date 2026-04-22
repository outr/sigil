package sigil.event

import fabric.rw.*
import lightdb.id.Id
import lightdb.time.Timestamp
import lightdb.util.Nowish
import sigil.conversation.{Conversation, Topic}
import sigil.participant.ParticipantId
import sigil.signal.EventState
import sigil.tool.{ToolInput, ToolSchema}

/**
 * Result of a capability-discovery tool call (currently `find_capability`).
 * Carries the matching [[ToolSchema]]s directly so the LLM has the full
 * descriptor — name, description, input definition, examples — to call any
 * match on its next turn. No prose summarization in the tool; the schema is
 * the result.
 *
 * Born `Active` so subscribers can react (e.g. UI preview of the
 * matched tools as they surface). The framework then broadcasts a
 * `StateDelta` transitioning it to `Complete`, at which point it's
 * historical — replay is silent.
 */
case class ToolResults(schemas: List[ToolSchema[? <: ToolInput]],
                       participantId: ParticipantId,
                       conversationId: Id[Conversation],
                       topicId: Id[Topic],
                       state: EventState = EventState.Active,
                       timestamp: Timestamp = Timestamp(Nowish()),
                       _id: Id[Event] = Event.id())
  extends Event derives RW {
  override def withState(state: EventState): Event = copy(state = state)
}
