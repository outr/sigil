package sigil.provider

import lightdb.id.Id
import rapid.Task
import sigil.{Sigil, SpaceId}
import sigil.db.Model

/**
 * Pre-shaped [[ProviderStrategyRecord]]s apps can optionally seed
 * into their database. Each strategy maps the framework's baseline
 * [[WorkType]]s to ordered model-id chains; the model ids are the
 * same identifiers `Sigil.providerFor` resolves, so apps need to
 * have those models reachable for the chains to fire.
 *
 * Three preset shapes:
 *
 *   - **`Balanced`** — typical defaults for chat+coding apps.
 *     Mixes Claude / GPT for general work; uses cheaper models for
 *     classification + summarization.
 *   - **`PremiumQuality`** — picks accuracy over cost. Top-tier
 *     models on every work type. Use when latency / spend isn't
 *     the constraint.
 *   - **`Economy`** — picks cheap models first; falls back to
 *     mid-tier only if a cheap model fails. Use for high-volume
 *     low-stakes flows (classification farms, ingest pipelines).
 *
 * Apps that want any of these in their DB call
 * `DefaultProviderStrategies.seed(sigil, space)` once on startup
 * (idempotent — checks for existing records by label before
 * inserting). Apps with bespoke preferences mint their own
 * `ProviderStrategyRecord` directly via
 * `Sigil.saveProviderStrategy`.
 *
 * The model ids referenced here are conservative defaults
 * (Voidcraft's empirically-tuned chains). Apps with different
 * provider mixes (only Anthropic, only DeepSeek, etc.) override
 * via [[customize]] before seeding, OR build their own records
 * from scratch.
 */
object DefaultProviderStrategies {

  /** Convenience — wrap a model-id string in a default
    * [[ModelCandidate]]. Apps that want per-candidate retry /
    * cooldown overrides build their own list. */
  private def m(id: String): ModelCandidate = ModelCandidate(Id[Model](id))

  /** Mid-cost / mid-quality default. Suits the "general assistant"
    * shape for most apps. */
  def balanced(space: SpaceId): ProviderStrategyRecord = ProviderStrategyRecord(
    space = space,
    label = "Balanced",
    defaultCandidates = List(m("anthropic/claude-sonnet-4-6"), m("openai/gpt-5.4"), m("anthropic/claude-haiku-4-5")),
    routeCandidates = Map(
      ConversationWork.value   -> List(m("anthropic/claude-sonnet-4-6"), m("openai/gpt-5.4"), m("anthropic/claude-haiku-4-5")),
      AnalysisWork.value       -> List(m("anthropic/claude-sonnet-4-6"), m("openai/gpt-5.4"), m("openai/o4-mini"), m("anthropic/claude-haiku-4-5")),
      CodingWork.value         -> List(m("anthropic/claude-opus-4-7"), m("openai/gpt-5.4"), m("anthropic/claude-sonnet-4-6")),
      ClassificationWork.value -> List(m("openai/gpt-5.4-mini"), m("anthropic/claude-haiku-4-5"), m("openai/gpt-5.4-nano")),
      CreativeWork.value       -> List(m("openai/gpt-5.4"), m("anthropic/claude-sonnet-4-6"), m("openai/gpt-5.4-mini")),
      SummarizationWork.value  -> List(m("openai/gpt-5.4-mini"), m("anthropic/claude-haiku-4-5"))
    ),
    metadata = Map("seedKey" -> "default-balanced")
  )

  /** Capability over cost — top-tier models on every work type. */
  def premiumQuality(space: SpaceId): ProviderStrategyRecord = ProviderStrategyRecord(
    space = space,
    label = "Premium Quality",
    defaultCandidates = List(m("anthropic/claude-opus-4-7"), m("openai/gpt-5.4"), m("google/gemini-2.5-pro")),
    routeCandidates = Map(
      ConversationWork.value   -> List(m("anthropic/claude-opus-4-7"), m("openai/gpt-5.4"), m("google/gemini-2.5-pro")),
      AnalysisWork.value       -> List(m("anthropic/claude-opus-4-7"), m("openai/gpt-5.4"), m("openai/o4")),
      CodingWork.value         -> List(m("anthropic/claude-opus-4-7"), m("openai/gpt-5.4"), m("anthropic/claude-sonnet-4-6")),
      ClassificationWork.value -> List(m("anthropic/claude-haiku-4-5"), m("openai/gpt-5.4-mini")),
      CreativeWork.value       -> List(m("anthropic/claude-opus-4-7"), m("openai/gpt-5.4"), m("google/gemini-2.5-pro")),
      SummarizationWork.value  -> List(m("anthropic/claude-sonnet-4-6"), m("openai/gpt-5.4-mini"))
    ),
    metadata = Map("seedKey" -> "default-premium")
  )

  /** Cheap-first. Falls back to mid-tier only when a cheap candidate
    * fails. Use for high-volume low-stakes flows. */
  def economy(space: SpaceId): ProviderStrategyRecord = ProviderStrategyRecord(
    space = space,
    label = "Economy",
    defaultCandidates = List(m("openai/gpt-5.4-mini"), m("anthropic/claude-haiku-4-5"), m("openai/gpt-5.4-nano"), m("openai/gpt-5.4")),
    routeCandidates = Map(
      ConversationWork.value   -> List(m("openai/gpt-5.4-mini"), m("anthropic/claude-haiku-4-5"), m("openai/gpt-5.4-nano")),
      AnalysisWork.value       -> List(m("anthropic/claude-haiku-4-5"), m("openai/gpt-5.4-mini"), m("anthropic/claude-sonnet-4-6")),
      CodingWork.value         -> List(m("anthropic/claude-sonnet-4-6"), m("openai/gpt-5.4"), m("openai/gpt-5.4-mini")),
      ClassificationWork.value -> List(m("openai/gpt-5.4-nano"), m("anthropic/claude-haiku-4-5"), m("openai/gpt-5.4-mini")),
      CreativeWork.value       -> List(m("openai/gpt-5.4-mini"), m("anthropic/claude-haiku-4-5")),
      SummarizationWork.value  -> List(m("openai/gpt-5.4-nano"), m("anthropic/claude-haiku-4-5"), m("openai/gpt-5.4-mini"))
    ),
    metadata = Map("seedKey" -> "default-economy")
  )

  /** All three preset records for a given space. */
  def all(space: SpaceId): List[ProviderStrategyRecord] =
    List(balanced(space), premiumQuality(space), economy(space))

  /**
   * Seed all three presets into the framework's database under the
   * given space. Idempotent — if a record with the same `seedKey`
   * metadata already exists, the existing record is preserved
   * (apps can edit them post-seed and re-seeds won't clobber
   * customizations).
   *
   * Returns the list of records as they live in the DB after the
   * call (whether freshly inserted or pre-existing).
   */
  def seed(sigil: Sigil, space: SpaceId): Task[List[ProviderStrategyRecord]] =
    sigil.withDB(_.providerStrategies.transaction(_.list)).flatMap { existing =>
      val existingByKey: Map[String, ProviderStrategyRecord] = existing.toList.flatMap { r =>
        r.metadata.get("seedKey").map(_ -> r)
      }.toMap

      Task.sequence(all(space).map { preset =>
        val key = preset.metadata.getOrElse("seedKey", "")
        existingByKey.get(key) match {
          case Some(extant) => Task.pure(extant)
          case None         => sigil.saveProviderStrategy(preset)
        }
      })
    }

  /**
   * Customize a preset before saving — useful when you want
   * Voidcraft's "Balanced" shape but with different model IDs
   * that match your provider mix.
   *
   * {{{
   *   val mine = DefaultProviderStrategies.customize(
   *     DefaultProviderStrategies.balanced(MySpace)
   *   ) { record =>
   *     record.copy(label = "Balanced (My Models)",
   *                 defaultCandidates = List(ModelCandidate(Id("my/preferred"))))
   *   }
   *   sigil.saveProviderStrategy(mine)
   * }}}
   */
  def customize(record: ProviderStrategyRecord)
               (f: ProviderStrategyRecord => ProviderStrategyRecord): ProviderStrategyRecord =
    f(record)
}
