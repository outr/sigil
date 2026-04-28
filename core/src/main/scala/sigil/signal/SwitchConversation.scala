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
 * Live signal flow continues for every conversation the viewer can
 * see; this Notice doesn't filter the stream. It's purely a request
 * for fresh state.
 */
case class SwitchConversation(conversationId: Id[Conversation]) extends Notice derives RW
