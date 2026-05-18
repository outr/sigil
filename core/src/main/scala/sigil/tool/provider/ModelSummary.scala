package sigil.tool.provider

import fabric.rw.*

/**
 * Compact view of a registered [[sigil.db.Model]] suitable for
 * agent-facing model-introspection tools. Carries the fields the
 * agent uses to disambiguate (`provider`, `model`), to budget
 * (`contextLength`), and to pick by capability (`description`,
 * `pricing`).
 *
 *   - `id` — fully-qualified id (`<provider>/<model>`).
 *   - `provider` / `model` — split from `id`.
 *   - `contextLength` — combined input+output context window in tokens.
 *   - `description` — upstream marketing/description text.
 *   - `pricing` — per-million-token pricing in USD (prompt + completion).
 *
 * Derived from the full [[sigil.db.Model]] catalog record; meant for
 * use in the typed output of [[ListModelsTool]] / [[CurrentModelTool]].
 */
case class ModelSummary(id: String,
                        provider: String,
                        model: String,
                        displayName: Option[String],
                        contextLength: Long,
                        description: String,
                        pricing: ModelPricingSummary)
  derives RW

/**
 * Pricing slice — per-million-token cost in USD. Mirrors the prompt /
 * completion fields of [[sigil.db.ModelPricing]]; auxiliary fields
 * (web-search, cache-read) are omitted because most agent decisions
 * only need the headline rate.
 */
case class ModelPricingSummary(prompt: BigDecimal, completion: BigDecimal) derives RW

object ModelSummary {

  /**
   * Build a [[ModelSummary]] from the full catalog record.
   */
  def from(m: sigil.db.Model): ModelSummary = ModelSummary(
    id = m._id.value,
    provider = m.provider,
    model = m.model,
    displayName = m.displayName,
    contextLength = m.contextLength,
    description = m.description,
    pricing = ModelPricingSummary(m.pricing.prompt, m.pricing.completion)
  )
}
