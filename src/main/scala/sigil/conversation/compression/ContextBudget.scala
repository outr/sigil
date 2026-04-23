package sigil.conversation.compression

import sigil.db.Model

/**
 * How many input tokens a single turn is allowed to spend before the
 * curator triggers compression. Resolved per-call against the target
 * model so percentage-based budgets adapt to the model's actual
 * context length.
 */
trait ContextBudget {
  def tokensFor(model: Model): Int
}
