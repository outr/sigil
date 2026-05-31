package sigil.tool.output

import lightdb.id.Id
import sigil.conversation.Conversation
import sigil.event.Event

/**
 * A prior paginated tool-call's output, resolved by reference for
 * composition. `rows` are every [[ToolOutputNode]] the originating call
 * drained — the whole container, depth-first ordered (level then ordinal)
 * — so a consuming tool sees the complete materialized result the source
 * tool produced, not just its first page.
 *
 * Tool output is a durable point-in-time observation; resolving a
 * reference reads exactly what the source tool saw when it ran, never a
 * re-execution.
 */
final case class ResolvedReference(conversationId: Id[Conversation],
                                   callId: Id[Event],
                                   rows: List[ToolOutputNode])
