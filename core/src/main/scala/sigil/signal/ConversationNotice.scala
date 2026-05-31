package sigil.signal

import lightdb.id.Id
import sigil.conversation.Conversation

/**
 * A [[Notice]] that pertains to a specific conversation. The
 * `conversationId` field every such notice already carries satisfies the
 * abstract member, so a notice opts in to conversation-scoped delivery
 * just by extending this instead of [[Notice]] —
 * [[sigil.transport.SignalTransport]] then delivers it only to
 * subscribers watching that conversation, never leaking it to a sibling
 * or parent view.
 *
 * Notices that are genuinely global (catalog snapshots, viewer-state
 * pulses) stay on plain [[Notice]] and pass every subscription. Notices
 * whose conversation is optional keep `conversationId: Option[...]` and
 * override `conversationScope` directly rather than extending this.
 */
trait ConversationNotice extends Notice {
  def conversationId: Id[Conversation]
  final override def conversationScope: Option[Id[Conversation]] = Some(conversationId)
}
