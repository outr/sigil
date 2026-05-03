package sigil.provider

import rapid.{Stream, Task}
import sigil.Sigil
import sigil.db.Model
import spice.http.HttpRequest

import java.util.concurrent.atomic.AtomicInteger

/**
 * Round-robins requests across a pool of equivalent [[Provider]]
 * instances — useful when an app holds multiple API keys against the
 * same upstream (e.g. two OpenAI accounts to multiply throughput) and
 * wants to spread load evenly.
 *
 * Falls over to the next provider when one raises an error the
 * [[errorClassifier]] categorises as
 * [[ErrorClassification.Fallthrough]] or
 * [[ErrorClassification.Retry]] — both treated as "try the next pool
 * member." [[ErrorClassification.Fatal]] errors propagate without
 * fallover (the whole pool is in trouble; surface to the caller).
 *
 * Distinct from [[ProviderStrategy]]:
 *   - `ProviderStrategy` picks among MODELS within ONE provider tier.
 *   - `LoadBalancedProvider` picks among multiple PROVIDER INSTANCES
 *     of the same tier. The two compose — apps wire a strategy that
 *     routes work to the load-balanced provider, which itself
 *     distributes across the pool.
 *
 * Each pool member retains its own [[RateLimiter]] (since the
 * limiter is per-API-key, and pool members typically have distinct
 * keys). The framework's `apply` consults each member's limiter
 * before dispatching.
 *
 * Pool ordering is round-robin via an [[AtomicInteger]]; thread-safe
 * for concurrent agent loops. The first attempt for a given request
 * picks the next-in-rotation; failures cycle through subsequent
 * members until one succeeds or all have been tried.
 */
case class LoadBalancedProvider(pool: Vector[Provider],
                                override protected val sigil: Sigil,
                                errorClassifier: ErrorClassifier = ErrorClassifier.Default)
  extends Provider {

  require(pool.nonEmpty, "LoadBalancedProvider requires at least one provider in the pool")

  /** All pool members must share a [[ProviderType]] for the framework's
   * `providerKey`-based registries to behave coherently. We use the
   * first member's type; mismatched pools will surface odd behavior
   * at the model-registry layer (rare in practice). */
  override def `type`: ProviderType = pool.head.`type`

  override def models: List[Model] = pool.head.models

  /** Round-robin cursor — each `apply` increments and reads. */
  private val cursor: AtomicInteger = new AtomicInteger(0)

  /** Pick the next pool member by round-robin position. */
  private def nextStartIndex: Int = {
    val n = math.floorMod(cursor.getAndIncrement(), pool.size)
    if (n < 0) n + pool.size else n
  }

  /** Walk the pool starting at `startIdx`; if a member's `call`
   * fails with a non-Fatal classification, try the next; on Fatal,
   * propagate the error. Returns the first successful stream. */
  override def call(input: ProviderCall): Stream[ProviderEvent] = {
    val start = nextStartIndex
    val ordered = (0 until pool.size).map(i => pool((start + i) % pool.size)).toList
    callChain(ordered, input)
  }

  override def httpRequestFor(input: ProviderCall): Task[HttpRequest] =
    pool(nextStartIndex).httpRequestFor(input)

  /** Recursive failover walk. We materialize the head's stream into a
   * Task[List[ProviderEvent]] via `toList.attempt` so we can pattern-
   * match on success/failure cleanly; events are buffered until the
   * pool member's call settles. The trade-off: a pool failover loses
   * partial streaming. Since failovers are rare and the agent loop's
   * downstream subscribers materialize anyway, this is acceptable. */
  private def callChain(remaining: List[Provider], input: ProviderCall): Stream[ProviderEvent] =
    remaining match {
      case Nil =>
        Stream.force(Task.error(new RuntimeException("LoadBalancedProvider: every pool member failed")))
      case head :: tail =>
        // Pool members typically carry distinct API keys → distinct
        // RateLimiter instances. The outer `apply` already drained the
        // load-balancer's own limiter (default NoOp); we drain the pool
        // member's limiter here so per-key pacing actually runs before
        // each call. Without this hop the per-member limiter the
        // class doc-string promises is unreachable from the live path.
        Stream.force(
          head.rateLimiter.acquire.flatMap(_ => head.call(input).toList.attempt).flatMap {
            case scala.util.Success(events) =>
              Task.pure(Stream.emits(events))
            case scala.util.Failure(err) =>
              errorClassifier.classify(err) match {
                case ErrorClassification.Fatal => Task.error(err)
                case _                          => Task.pure(callChain(tail, input))
              }
          }
        )
    }
}
