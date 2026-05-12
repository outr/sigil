package sigil.provider

import lightdb.id.Id
import rapid.Task
import sigil.TurnContext
import sigil.db.Model

import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * Routes a [[WorkType]] to an ordered list of [[ModelCandidate]]s —
 * first candidate is preferred, subsequent are fallbacks. Strategies
 * are the runtime layer between an agent's request and a concrete
 * provider call: the agent supplies the work type, the strategy
 * picks the model.
 *
 * Sigil ships two implementations:
 *   - [[ProviderStrategy.single]] — pin a single model for every
 *     work type (no fallback). What the `switch_model` tool builds
 *     when the user asks to switch to a specific model.
 *   - [[ProviderStrategy.routed]] — per-work-type chains plus a
 *     default. Tracks per-candidate cooldowns when one fails so
 *     the rotation gives transient failures time to clear.
 *
 * Apps with custom semantics (round-robin, load-shed, cost-bucket
 * routing) implement [[ProviderStrategy]] themselves and return
 * their instance from `Sigil.resolveProviderStrategy`.
 */
trait ProviderStrategy {

  /** Ordered model candidates for a given work type. First entry
    * is preferred, rest are fallbacks. Apps that don't differentiate
    * by work type return the same list for every input. */
  def candidates(workType: WorkType): List[ModelCandidate]

  /** Error classifier — the runtime consults this to decide whether
    * a failed candidate should be retried, fallen through, or
    * surfaced. Default suits common HTTP signatures; apps override
    * for provider-specific exception types. */
  def errorClassifier: ErrorClassifier = ErrorClassifier.Default

  /** Notify the strategy that a candidate failed for a work type.
    * Stateful strategies (e.g. cooldown trackers) record the failure
    * here so [[availableCandidates]] can skip the candidate for a
    * period. The default no-op suits stateless strategies. */
  def reportFailure(modelId: Id[Model], workType: WorkType): Unit = ()

  /** Currently-eligible candidates — typically `candidates(workType)`
    * minus those still in cooldown. The runtime uses this rather
    * than `candidates` directly when picking a candidate to call. */
  def availableCandidates(workType: WorkType): List[ModelCandidate] = candidates(workType)

  /** Bug #128 — per-message [[WorkType]] inference. `None` means
    * the strategy doesn't classify per message; the framework falls
    * back to `mode.workType`. Apps that want intelligent routing wire
    * a cheap classifier (typically a small local model). Returns
    * `None` from `ProviderStrategy.single`; routed strategies expose
    * the value set at construction. */
  def inferWorkType: Option[ProviderStrategy.InferWorkType] = None

  /** Bug #128 — per-message [[Complexity]] inference. Mirror of
    * [[inferWorkType]]; `None` means the framework defaults to
    * [[defaultComplexity]] for every turn. */
  def inferComplexity: Option[ProviderStrategy.InferComplexity] = None

  /** Bug #154 — complexity tier used when no per-message
    * classification runs (empty user text on greet / synthetic
    * turns, classifier-disabled, classifier failure). Apps
    * biased toward cost-first routing (local model handles every
    * greet) override with [[Complexity.Low]]; apps that want
    * frontier-by-default for empty-text greets override with
    * [[Complexity.VeryHigh]]. The framework's previous
    * hardcoded `Complexity.Medium` becomes the trait's default
    * — existing apps see no behavioural change. */
  def defaultComplexity: Complexity = Complexity.Medium

  /** True when at least one [[WorkType]] chain differs from another
    * (or from default). When every chain is identical, classifying
    * workType can't change the candidate list — skip the classifier
    * call. Precomputed at construction; O(1) at turn time. */
  def workTypeMatters: Boolean = false

  /** True when at least one chain reachable from this strategy has
    * candidates with differing [[ModelCandidate.supportedComplexity]]
    * sets. When every candidate supports the same tiers, complexity
    * classification can't filter anything. Precomputed at
    * construction; cheap to check per-chain at turn time. */
  def complexityMatters(workType: WorkType): Boolean = false

  /** Should the framework run the workType classifier for this
    * turn? Composes [[inferWorkType]]'s availability with the
    * cheap-cost [[workTypeMatters]] gate. */
  final def shouldClassifyWorkType: Boolean =
    inferWorkType.isDefined && workTypeMatters

  /** Should the framework run the complexity classifier for this
    * turn? Composes [[inferComplexity]]'s availability with a per-
    * chain check — classifying complexity only matters if the
    * resolved chain has candidates with differing
    * `supportedComplexity` sets. */
  final def shouldClassifyComplexity(workType: WorkType): Boolean =
    inferComplexity.isDefined && complexityMatters(workType)
}

object ProviderStrategy {

  /** Bug #128 — classifier callback for per-message [[WorkType]]
    * inference. Receives the user's latest message text plus the
    * full TurnContext (chain, conversation, modelId fallbacks);
    * returns the classified WorkType. Apps wire a `ConsultTool.invoke`
    * against a cheap local model with a structured-output schema. */
  type InferWorkType = (String, TurnContext) => Task[WorkType]

  /** Bug #128 — classifier callback for per-message [[Complexity]]
    * inference. Mirror of [[InferWorkType]]; same call shape, returns
    * the tier. Apps typically combine both signals into one classifier
    * round-trip to save tokens. */
  type InferComplexity = (String, TurnContext) => Task[Complexity]

  /** Pin a single model across every work type. No fallback,
    * no cooldown, no per-work-type routing — what the `switch_model`
    * tool builds for ad-hoc per-conversation overrides. */
  def single(modelId: Id[Model],
             settings: GenerationSettings = GenerationSettings()): ProviderStrategy =
    new SingleModelStrategy(modelId, settings)

  /** Per-work-type chains with a default fallback. `routes` keys
    * by `WorkType.value`; missing keys fall through to `default`.
    * Cooldown tracking lives on the returned instance — apps reusing
    * the same strategy across many calls get failure-aware routing
    * without extra wiring.
    *
    * Bug #128 — opt-in per-message routing: pass `inferWorkType` /
    * `inferComplexity` callbacks to enable classifier-driven
    * candidate selection. The strategy precomputes
    * [[ProviderStrategy.workTypeMatters]] /
    * [[ProviderStrategy.complexityMatters]] from the routes table so
    * classifier round-trips happen only when their outcome can
    * actually shape the resolved candidate. Default `None` for both
    * preserves today's behavior (mode.workType + Complexity.Medium). */
  def routed(default: List[ModelCandidate],
             routes: Map[WorkType, List[ModelCandidate]] = Map.empty,
             errorClassifier: ErrorClassifier = ErrorClassifier.Default,
             inferWorkType: Option[InferWorkType] = None,
             inferComplexity: Option[InferComplexity] = None,
             defaultComplexity: Complexity = Complexity.Medium): ProviderStrategy =
    new RoutedStrategy(default, routes, errorClassifier, inferWorkType, inferComplexity, defaultComplexity)
}

/** Single-model strategy — every work type returns the same one-element list. */
private final class SingleModelStrategy(modelId: Id[Model],
                                        settings: GenerationSettings) extends ProviderStrategy {
  private val pinned = List(ModelCandidate(modelId, settings))
  override def candidates(workType: WorkType): List[ModelCandidate] = pinned
}

/** Routed strategy — per-work-type chains with cooldown bookkeeping. */
private final class RoutedStrategy(default: List[ModelCandidate],
                                   routes: Map[WorkType, List[ModelCandidate]],
                                   override val errorClassifier: ErrorClassifier,
                                   override val inferWorkType: Option[ProviderStrategy.InferWorkType],
                                   override val inferComplexity: Option[ProviderStrategy.InferComplexity],
                                   override val defaultComplexity: Complexity)
  extends ProviderStrategy {

  private val cooldowns: ConcurrentHashMap[Id[Model], Instant] = new ConcurrentHashMap[Id[Model], Instant]()

  /** Bug #128 — precomputed: true when at least one chain differs
    * from another (or from default). When every chain is identical,
    * classifying workType can't change the candidate list. */
  override val workTypeMatters: Boolean = {
    val allChains: Set[List[ModelCandidate]] = routes.values.toSet + default
    allChains.size > 1
  }

  override def candidates(workType: WorkType): List[ModelCandidate] =
    routes.getOrElse(workType, default)

  override def complexityMatters(workType: WorkType): Boolean = {
    val chain = candidates(workType)
    chain.map(_.supportedComplexity).distinct.size > 1
  }

  override def reportFailure(modelId: Id[Model], workType: WorkType): Unit = {
    candidates(workType).find(_.modelId == modelId).foreach { c =>
      cooldowns.put(modelId, Instant.now().plusMillis(c.cooldownMs))
    }
  }

  override def availableCandidates(workType: WorkType): List[ModelCandidate] = {
    val now = Instant.now()
    candidates(workType).filter { c =>
      val until = cooldowns.get(c.modelId)
      until == null || now.isAfter(until)
    }
  }
}
