package sigil.provider.openai

import lightdb.id.Id
import sigil.cache.ModelRegistry
import sigil.db.Model

/**
 * OpenAI-specific constants. Model metadata (context, pricing,
 * tokenizer, etc.) lives in [[sigil.cache.ModelRegistry]] — populated by
 * [[sigil.controller.OpenRouter.refreshModels]] — and is read fresh on
 * each access via `Provider.models`.
 */
object OpenAI {
  val Provider: String = "openai"

  /**
   * Strip the `openai/` provider prefix from a sigil model id so the
   * wire request carries the bare OpenAI id.
   */
  def stripProviderPrefix(sigilModelId: String): String = {
    val prefix = s"$Provider/"
    if (sigilModelId.startsWith(prefix)) sigilModelId.drop(prefix.length) else sigilModelId
  }

  /**
   * Cold-cache safety net for [[OpenAIProvider]]'s sampling-param
   * filter. Models whose name starts with one of these prefixes
   * have fixed sampling (temperature locked to 1.0, no `top_p`)
   * and reject requests carrying those fields.
   *
   * The provider's primary check goes through
   * [[sigil.Sigil.supportsParameter]] (driven by
   * `Model.supportedParameters` from the registry); this list is
   * the fallback when the registry hasn't been populated yet
   * (first boot before `OpenRouter.refreshModels`, offline
   * sessions, etc.). Adding a new restricted family is one line
   * here — the call sites stay catalog-driven.
   */
  val fixedSamplingPrefixes: Set[String] = Set("gpt-5", "o1", "o3")

  /**
   * OpenAI model families that emit `reasoning` output items in the
   * Responses API stream and require the corresponding state to be
   * either replayed (encrypted_content round-trip) or recovered via
   * `previous_response_id` on subsequent requests.
   *
   * `OpenAIProvider` uses this to opt the request into
   * `reasoning.summary = "auto"` and
   * `include = ["reasoning.encrypted_content"]` so the response
   * carries content the framework can persist + replay. Other
   * models (gpt-4o, gpt-4.1, gpt-3.5) don't emit reasoning items
   * and some endpoints reject the include keyword as unknown,
   * so the opt-in is gated.
   *
   * Prefix-based detection mirrors [[fixedSamplingPrefixes]] — same
   * cold-cache rationale: a `Model.family` field would be cleaner
   * long-term but the registry isn't always populated when the
   * filter runs (first boot before `OpenRouter.refreshModels`,
   * offline sessions). `o4` is included pre-emptively; adding
   * a new family is a one-line change here.
   */
  val reasoningModelPrefixes: Set[String] = Set("gpt-5", "o1", "o3", "o4")

  /**
   * Reasoning-effort token sets per OpenAI family, used by
   * [[reasoningEffortLevelsFor]] when a registered [[Model]] doesn't
   * declare its own `supportedReasoningEffortLevels`. Probed from
   * the families' actual API behavior; gpt-5.5 replaced `minimal`
   * with `none` and added `xhigh` at the high end.
   *
   * Lookup is suffix-prefix-matched on the model's slug portion
   * (the trailing segment after `<provider>/`), longest-match-first
   * so `gpt-5.5` resolves before `gpt-5`.
   */
  private val effortLevelsByFamily: List[(String, Set[String])] = List(
    "gpt-5.5" -> Set("none", "low", "medium", "high", "xhigh"),
    "gpt-5_5" -> Set("none", "low", "medium", "high", "xhigh"),
    "gpt-5.4" -> Set("none", "low", "medium", "high", "xhigh"),
    "gpt-5_4" -> Set("none", "low", "medium", "high", "xhigh"),
    "gpt-5" -> Set("minimal", "low", "medium", "high"),
    "o1" -> Set("minimal", "low", "medium", "high"),
    "o3" -> Set("minimal", "low", "medium", "high"),
    "o4" -> Set("minimal", "low", "medium", "high")
  )

  /**
   * Resolve the set of `reasoning.effort` levels a model accepts.
   * Prefers the model registry's `supportedReasoningEffortLevels`
   * declaration; falls back to the family heuristic when the
   * registry is silent. Returns an empty set for non-reasoning
   * models so callers can treat "no levels" as "skip the reasoning
   * field entirely".
   */
  def reasoningEffortLevelsFor(modelId: Id[Model], registry: ModelRegistry): Set[String] =
    registry.find(modelId).flatMap(_.supportedReasoningEffortLevels).getOrElse(effortLevelsFromSlug(modelId.value))

  /**
   * Family-heuristic effort-level set, derived from the model id's
   * slug suffix. Public so non-Sigil callers (custom providers,
   * registry population scripts) can reuse the same mapping.
   */
  def effortLevelsFromSlug(modelId: String): Set[String] = {
    val slug = modelId.split("/").last
    effortLevelsByFamily.find { case (prefix, _) => slug.startsWith(prefix) } match {
      case Some((_, levels)) => levels
      case None => Set.empty
    }
  }

  /**
   * Order used by [[lowestEffort]] / [[highestEffort]]. Mirrors the
   * monotonic "less reasoning -> more reasoning" axis: any level not
   * named here sorts at the end and is treated as "max-ish".
   */
  private val effortOrdering: List[String] =
    List("none", "minimal", "low", "medium", "high", "xhigh")

  /**
   * Pick the lowest-cost effort level from `levels`; falls back to
   * `"minimal"` when the set is empty so non-reasoning models still
   * have a sensible default (matches the legacy single-string
   * hardcode the provider used before the registry hook).
   */
  def lowestEffort(levels: Set[String]): String =
    if (levels.isEmpty) "minimal"
    else effortOrdering.find(levels.contains).getOrElse(levels.head)

  /**
   * Pick the highest-effort level from `levels`; falls back to
   * `"high"` when empty.
   */
  def highestEffort(levels: Set[String]): String =
    if (levels.isEmpty) "high"
    else effortOrdering.reverse.find(levels.contains).getOrElse(levels.last)

  /**
   * Translate a sigil [[sigil.provider.Effort]] to the wire string
   * the active model accepts. `Low` / `Medium` / `High` map directly
   * when they're in the supported set; `Max` / `Custom` resolve to
   * the highest level the model accepts (so gpt-5.5's `Max` picks
   * `xhigh` rather than `high`). Falls back to
   * [[sigil.provider.Effort.openAIEffortLevel]] when `levels` is
   * empty so non-reasoning models keep the legacy literal mapping.
   */
  def effortLevelFor(effort: sigil.provider.Effort, levels: Set[String]): String =
    if (levels.isEmpty) sigil.provider.Effort.openAIEffortLevel(effort)
    else effort match {
      case sigil.provider.Effort.Low if levels.contains("low") => "low"
      case sigil.provider.Effort.Medium if levels.contains("medium") => "medium"
      case sigil.provider.Effort.High if levels.contains("high") => "high"
      case sigil.provider.Effort.Max | sigil.provider.Effort.Custom(_) =>
        highestEffort(levels)
      case _ => highestEffort(levels)
    }
}
