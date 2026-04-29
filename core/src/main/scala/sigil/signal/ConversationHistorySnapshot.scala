package sigil.signal

import fabric.rw.*
import lightdb.id.Id
import sigil.conversation.Conversation
import sigil.event.Event

/**
 * Server→client [[Notice]] carrying a paginated window of older
 * conversation events. Sent in reply to a
 * [[RequestConversationHistory]].
 *
 * `events` is in chronological order (oldest first), capped at the
 * requested `limit`. `hasMore = true` means more events exist
 * before the oldest one in this window — the client can keep
 * paginating by passing `events.head.timestamp.value` as the next
 * `beforeMs`.
 *
 * Empty `events` with `hasMore = false` means the client has reached
 * the start of the conversation.
 */
case class ConversationHistorySnapshot(conversationId: Id[Conversation],
                                       events: Vector[Event],
                                       hasMore: Boolean = false) extends Notice derives RW
