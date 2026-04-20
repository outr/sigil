package sigil.conversation

import fabric.rw.*

/**
 * Typed key for entries in [[ConversationContext.extraContext]] and
 * [[ParticipantContext.extraContext]]. Apps define keys as constants —
 * makes them greppable and ensures re-inserting with the same key
 * replaces the prior value rather than accumulating duplicates.
 */
case class ContextKey(value: String) derives RW
