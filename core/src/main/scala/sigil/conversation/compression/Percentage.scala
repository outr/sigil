package sigil.conversation.compression

import sigil.db.Model

/**
 * Budget as a fraction of the model's declared `contextLength` — e.g.
 * `Percentage(0.8)` reserves 20% for the response plus headroom. Default
 * 0.8 matches what most production deployments pick.
 */
case class Percentage(pct: Double = 0.8) extends ContextBudget {
  override def tokensFor(model: Model): Int =
    math.max(1, (model.contextLength.toDouble * pct).toInt)
}
