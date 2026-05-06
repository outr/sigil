package sigil.signal

import fabric.rw.*
import lightdb.id.Id
import sigil.conversation.Conversation
import sigil.event.Event

/**
 * Server→client [[Notice]] carrying the most-recent event history
 * for a conversation, bounded by the [[SwitchConversation.limit]] the
 * client requested.
 *
 * `recentEvents` is the trailing window in chronological order —
 * `head` is the oldest, `last` is the newest. `hasMore = true` when
 * older events exist beyond the window; clients fetch them via
 * [[RequestConversationHistory]] using `recentEvents.head`'s
 * timestamp as the `beforeMs` cursor.
 *
 * Bug #26 — the legacy `view: ConversationView` field is gone;
 * clients render rolling-window frames directly from each event's
 * [[sigil.event.Event.contextFrame]].
 *
 * Live updates take over from the receipt of this snapshot forward
 * (any subsequent Events / Deltas for this conversation flow through
 * the standard signal stream).
 */
case class ConversationSnapshot(conversationId: Id[Conversation],
                                recentEvents: Vector[Event],
                                hasMore: Boolean = false) extends Notice derives RW
