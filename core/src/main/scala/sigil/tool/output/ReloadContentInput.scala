package sigil.tool.output

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
 *     content, paginated; a summary id returns the list of events the
 *     summary covers (each with a snippet + its id) so the agent drills
 *     into one with `reload_content("<eventId>")`.
 *   - `page` — zero-indexed page over the reloaded content's chunks.
 *   - `pageSize` — defaults to 50 (max 500).
 *   - `conversationId` — sigil #289 — target a specific conversation.
 *     When unset (default), the caller's current conversation is used;
 *     cross-conversation reads are gated by
 *     [[sigil.Sigil.canReadConversation]] (parent / worker only).
 */
case class ReloadContentInput(referenceId: String,
                              page: Int = 0,
                              pageSize: Int = 50,
                              conversationId: Option[Id[Conversation]] = None) extends ToolInput derives RW
