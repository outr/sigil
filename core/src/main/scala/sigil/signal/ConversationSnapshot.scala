package sigil.signal

import fabric.rw.*
import lightdb.id.Id
import sigil.conversation.{Conversation, ConversationView}
import sigil.event.Event

/**
 * Server→client [[Notice]] carrying the full current state of one
 * conversation: its [[ConversationView]] projection plus the recent
 * [[sigil.event.Event]] history needed for the UI to render. Sent in
 * reply to a [[SwitchConversation]] (the framework's default
 * [[sigil.Sigil.handleNotice]] arm) but valid as an unsolicited push.
 *
 * Snapshots are atomic — the whole payload arrives in one Notice so
 * the UI can render the conversation without accumulating events
 * over time. Live updates take over from the receipt of this
 * snapshot forward (any subsequent Events / Deltas for this
 * conversation flow through the standard signal stream).
 */
case class ConversationSnapshot(conversationId: Id[Conversation],
                                view: ConversationView,
                                recentEvents: Vector[Event]) extends Notice derives RW
