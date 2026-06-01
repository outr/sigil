package sigil.tool.context

import fabric.rw.*
import lightdb.id.Id
import sigil.conversation.Conversation
import sigil.tool.ToolInput

/**
 * Reload content that context virtualization (#316) elided to save
 * budget, by id.
 *
 *   - `referenceId` — an event id (from an elided entry's
 *     `[… reload_content("<id>")]` marker) reloads that event's full
 *     content; a summary id returns the list of events the summary
 *     covers (each with a snippet + its id) so the agent drills into one
 *     with `reload_content("<eventId>")`.
 *   - `conversationId` — sigil #289 — target a specific conversation.
 *     When unset (default), the caller's current conversation is used;
 *     cross-conversation reads are gated by
 *     [[sigil.Sigil.canReadConversation]] (parent / worker only).
 */
case class ReloadContentInput(referenceId: String,
                              conversationId: Option[Id[Conversation]] = None) extends ToolInput derives RW
