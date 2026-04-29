package sigil.provider

import sigil.PolyType

/**
 * Open category of work the LLM is being asked to do — used by
 * [[ProviderStrategy]] to route a request to the appropriate model
 * chain. Strategies map work types to per-type fallback chains so a
 * cheap model can handle classification while a stronger model
 * handles analysis on the same conversation.
 *
 * Sigil ships a baseline set of common work types
 * ([[ConversationWork]], [[CodingWork]], [[AnalysisWork]],
 * [[ClassificationWork]], [[CreativeWork]], [[SummarizationWork]]).
 * Apps define their own subtypes and register them via
 * `Sigil.workTypeRegistrations`:
 *
 * {{{
 *   case object NewsExtractionWork extends WorkType {
 *     override val value: String = "news-extraction"
 *   }
 *
 *   override protected def workTypeRegistrations: List[WorkType] =
 *     List(NewsExtractionWork)
 * }}}
 *
 * Open `PolyType` rather than a closed enum because different
 * deployments routinely have completely different categories of
 * work (medical triage, legal-clause review, etc.) and the framework
 * shouldn't pretend it knows them all.
 */
trait WorkType {
  /** Stable string identifier — what `ProviderStrategyRecord.routes`
    * keys on, and what the wire serialization round-trips through.
    * Conventional kebab-case. Keep stable across renames; consumers
    * persist this verbatim. */
  def value: String
}

object WorkType extends PolyType[WorkType]

/** General chat / Q&A — the default work type for all agent turns
  * unless an app overrides per-agent or per-mode. */
case object ConversationWork extends WorkType {
  override val value: String = "conversation"
}

/** Code generation, editing, review. */
case object CodingWork extends WorkType {
  override val value: String = "coding"
}

/** Reasoning, data analysis, complex problems. */
case object AnalysisWork extends WorkType {
  override val value: String = "analysis"
}

/** Quick intent checks, relevance scoring, classification. */
case object ClassificationWork extends WorkType {
  override val value: String = "classification"
}

/** Writing, brainstorming, synthesis, polishing. */
case object CreativeWork extends WorkType {
  override val value: String = "creative"
}

/** Condensing content, title generation, context compression. */
case object SummarizationWork extends WorkType {
  override val value: String = "summarization"
}
