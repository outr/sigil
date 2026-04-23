package sigil.conversation.compression

import sigil.db.Model

/**
 * Budget as an absolute token count, regardless of the model's context
 * length. Use when you have a hard cap you want to enforce uniformly
 * across providers (e.g. cost control on the input side).
 */
case class Fixed(tokens: Int) extends ContextBudget {
  override def tokensFor(model: Model): Int = tokens
}
