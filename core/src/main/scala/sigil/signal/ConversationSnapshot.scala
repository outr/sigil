package sigil.signal

import fabric.rw.*
import lightdb.id.Id
import sigil.conversation.{Conversation, ConversationView}
import sigil.event.Event

/**
 * Server→client [[Notice]] carrying the current state of one
 * conversation: its [[ConversationView]] projection plus the most
 * recent [[sigil.event.Event]] history bounded by the
 * [[SwitchConversation.limit]] the client requested.
 *
 * `recentEvents` is the trailing window in chronological order —
 * `head` is the oldest, `last` is the newest. `hasMore = true`
 * when older events exist beyond the window; clients fetch them
 * via [[RequestConversationHistory]] using `recentEvents.head`'s
 * timestamp as the `beforeMs` cursor.
 *
 * Live updates take over from the receipt of this snapshot forward
 * (any subsequent Events / Deltas for this conversation flow
 * through the standard signal stream).
 */
case class ConversationSnapshot(conversationId: Id[Conversation],
                                view: ConversationView,
                                recentEvents: Vector[Event],
                                hasMore: Boolean = false) extends Notice derives RW
