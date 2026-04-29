package sigil.signal

import fabric.rw.*
import lightdb.id.Id
import sigil.conversation.Conversation

/**
 * Client→server [[Notice]]: "give me older events for this
 * conversation, before this cursor." Used to page back through long
 * conversation histories — the initial [[ConversationSnapshot]]
 * delivers a bounded window via [[SwitchConversation.limit]], and
 * clients fetch older windows on scroll-near-top by sending this.
 *
 * `beforeMs` is exclusive (epoch milliseconds) — the server returns
 * events whose `timestamp.value < beforeMs`, so clients can pass
 * the oldest event they currently hold to fetch the next page back.
 *
 * `limit` caps the response. Default 100; pass higher for batch
 * fetches.
 *
 * The framework's default [[sigil.Sigil.handleNotice]] arm replies
 * with a [[ConversationHistorySnapshot]] in chronological order
 * (oldest first), with `hasMore = true` when older events still
 * exist past the window.
 */
case class RequestConversationHistory(conversationId: Id[Conversation],
                                      beforeMs: Long,
                                      limit: Int = 100) extends Notice derives RW
