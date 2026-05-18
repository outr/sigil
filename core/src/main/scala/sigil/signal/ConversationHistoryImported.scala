package sigil.signal

import fabric.rw.*
import lightdb.id.Id
import sigil.conversation.Conversation

/**
 * Server→client [[Notice]] emitted by [[sigil.Sigil.publishHistorical]]
 * after a bulk-import of historical events settles.
 *
 * Light shape — only the conversation id and the count of events
 * persisted. Carrying the events inline would balloon the wire frame
 * for multi-thousand-event imports (Claude Code session imports
 * realistically hit 7k+ events). Clients that care about the new
 * tail re-request a fresh [[ConversationSnapshot]] /
 * [[ConversationHistorySnapshot]]; clients with no active view of
 * the conversation ignore the notice.
 */
case class ConversationHistoryImported(conversationId: Id[Conversation],
                                       addedCount: Int)
  extends Notice derives RW
