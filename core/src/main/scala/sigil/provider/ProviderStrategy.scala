package sigil.provider

import lightdb.id.Id
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
}

object ProviderStrategy {

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
    * without extra wiring. */
  def routed(default: List[ModelCandidate],
             routes: Map[WorkType, List[ModelCandidate]] = Map.empty,
             errorClassifier: ErrorClassifier = ErrorClassifier.Default): ProviderStrategy =
    new RoutedStrategy(default, routes, errorClassifier)
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
                                   override val errorClassifier: ErrorClassifier) extends ProviderStrategy {

  private val cooldowns: ConcurrentHashMap[Id[Model], Instant] = new ConcurrentHashMap[Id[Model], Instant]()

  override def candidates(workType: WorkType): List[ModelCandidate] =
    routes.getOrElse(workType, default)

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
