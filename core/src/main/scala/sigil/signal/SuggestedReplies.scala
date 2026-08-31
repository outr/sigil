package sigil.signal

import fabric.rw.*
import lightdb.id.Id
import sigil.conversation.Conversation
import sigil.event.Event

/**
 * Framework-predicted next messages for the USER, fired after an
 * agent turn settles with a user-visible reply. Enabled by setting
 * [[sigil.Sigil.replySuggestions]]; nothing is emitted when it's
 * `None`.
 *
 * Each string is phrased in the user's own voice, so a composer can
 * use `suggestions.headOption` as inline type-ahead text and a chip
 * UI can render the whole list. `forMessageId` is the settled reply
 * the prediction was made against — clients drop the notice when
 * that message is no longer the conversation's tail.
 *
 * Transient: never persisted, never replayed. Stale suggestions die
 * with the connection and each new notice replaces the previous one
 * for the conversation. Notices carry no [[sigil.event.MessageVisibility]]
 * of their own, so an agent viewer's subscription receives this too —
 * agent-facing clients ignore it; it is guidance for a human composer.
 */
case class SuggestedReplies(conversationId: Id[Conversation],
                            forMessageId: Id[Event],
                            suggestions: List[String])
  extends ConversationNotice derives RW
