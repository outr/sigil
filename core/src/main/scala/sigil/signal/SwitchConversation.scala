package sigil.signal

import fabric.rw.*
import lightdb.id.Id
import sigil.conversation.Conversation

/**
 * Client→server [[Notice]]: "I'm now focused on this conversation —
 * send me its current state." Fired by UIs when the user opens or
 * switches to a different conversation. The framework's default
 * [[sigil.Sigil.handleNotice]] arm replies with a
 * [[ConversationSnapshot]] for the requested conversation.
 *
 * `limit` bounds the snapshot's `recentEvents` to the most-recent N
 * events; older events are paginated through
 * [[RequestConversationHistory]]. Default is 100 — small enough to
 * render fast, large enough that most users never need to scroll
 * back. Pass a larger value for batch / migration flows that need
 * the full history at once.
 *
 * Live signal flow continues for every conversation the viewer can
 * see; this Notice doesn't filter the stream. It's purely a request
 * for fresh state.
 */
case class SwitchConversation(conversationId: Id[Conversation],
                              limit: Int = 100) extends Notice derives RW
