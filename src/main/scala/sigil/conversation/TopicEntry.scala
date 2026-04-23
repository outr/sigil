package sigil.conversation

import fabric.rw.*
import lightdb.id.Id

/**
 * A denormalized snapshot of a [[Topic]] used to populate the
 * [[Conversation.topics]] navigation stack.
 *
 * Each entry carries the Topic's id plus its current `label` and
 * `summary`. The denormalization avoids a join when the conversation
 * is rendered (system prompt, sidebar, search index), at the cost of
 * keeping these fields in sync when a Topic is renamed: any
 * [[sigil.event.TopicChange]] of kind [[sigil.event.TopicChangeKind.Rename]]
 * walks the stack and updates entries whose `id` matches.
 *
 * The stack itself models conversational navigation:
 *   - `head` of the list is the oldest topic, `last` is the active one
 *   - A Switch to a brand-new label pushes a new entry
 *   - A Switch whose label is already on the stack truncates back to
 *     that entry — this is how "return to a previous topic" works
 *   - A Rename mutates the entry in place (keeping its position)
 */
case class TopicEntry(id: Id[Topic], label: String, summary: String) derives RW
