package sigil

import lightdb.id.Id
import sigil.conversation.Conversation

/**
 * Raised by conversation-management APIs ([[Sigil.addParticipant]],
 * [[Sigil.removeParticipant]]) when the supplied id doesn't resolve
 * to a stored [[sigil.conversation.Conversation]] record.
 *
 * Apps that want soft-failure semantics should look the conversation
 * up themselves first; this exception exists so the management calls
 * have an honest failure mode rather than silently no-op'ing on a
 * typo'd id.
 */
final class ConversationNotFoundException(val conversationId: Id[Conversation])
  extends RuntimeException(s"Conversation not found: ${conversationId.value}")
