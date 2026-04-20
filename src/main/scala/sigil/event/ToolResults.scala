package sigil.event

import fabric.rw.*
import lightdb.id.Id
import lightdb.time.Timestamp
import lightdb.util.Nowish
import sigil.conversation.Conversation
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
 * Atomic — emitted at `Complete`. Visible to both UI and Model.
 */
case class ToolResults(schemas: List[ToolSchema[? <: ToolInput]],
                       participantId: ParticipantId,
                       conversationId: Id[Conversation],
                       state: EventState = EventState.Complete,
                       visibility: Set[EventVisibility] = Set(EventVisibility.UI, EventVisibility.Model),
                       timestamp: Timestamp = Timestamp(Nowish()),
                       _id: Id[Event] = Event.id()) extends Event derives RW
