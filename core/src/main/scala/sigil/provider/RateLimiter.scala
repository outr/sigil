package sigil.provider

import rapid.Task

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.duration.*

/**
 * Proactive rate-limit pacer. Concrete providers feed
 * [[RateLimiter.observe]] the headers from every response (OpenAI's
 * `x-ratelimit-remaining-*`, Anthropic's `anthropic-ratelimit-*`,
 * etc.); [[RateLimiter.acquire]] runs before each outgoing request
 * and `Task.sleep`s if the headers said the bucket is near-empty.
 *
 * Distinct from [[ErrorClassifier]] / [[ProviderStrategy]]:
 *   - `ErrorClassifier` reacts to a 429 *after* it happens and
 *     decides whether to retry / fall through / give up.
 *   - `ProviderStrategy` chooses among model candidates and tracks
 *     per-candidate cooldowns.
 *   - `RateLimiter` paces *outgoing* traffic from response-header
 *     hints so the 429 never fires in the first place.
 *
 * Defaults are conservative — a quarter-second sleep when the bucket
 * is below 5% capacity, scaling to one-second when below 1%. Apps
 * tune via [[RateLimiterConfig]].
 *
 * The framework ships a per-API-key registry (see
 * [[RateLimiter.forKey]]) so multiple providers sharing the same
 * upstream account share one limiter — the rate limit is per-key,
 * not per-provider-instance.
 */
trait RateLimiter {
  /** Suspend until the limiter believes it's safe to send. Returns
   * immediately when no limit data has been observed yet (apps using
   * the limiter against a fresh provider don't pay sleep cost on the
   * first request). */
  def acquire: Task[Unit]

  /** Update the limiter's view of headroom from a fresh response. The
   * concrete impl reads each provider's specific header names — the
   * trait stays neutral. */
  def observe(remainingRequests: Option[Long],
              remainingTokens: Option[Long],
              resetSeconds: Option[Long],
              retryAfter: Option[FiniteDuration]): Unit
}

case class RateLimiterConfig(softFloor: Double = 0.05,
                             softSleep: FiniteDuration = 250.millis,
                             hardFloor: Double = 0.01,
                             hardSleep: FiniteDuration = 1.second)

object RateLimiter {

  /** Default limiter — tracks the most-recent `observe` call's
   * proportional headroom and waits before sending when the bucket is
   * below the configured floors. Thread-safe; one instance can be
   * shared across many concurrent agent loops. */
  def default(config: RateLimiterConfig = RateLimiterConfig()): RateLimiter = new ProportionalRateLimiter(config)

  /** A no-op limiter — `acquire` returns immediately, `observe`
   * discards. The default Provider hook returns this so apps that
   * don't care about rate limiting pay zero overhead. */
  val NoOp: RateLimiter = new RateLimiter {
    override val acquire: Task[Unit] = Task.unit
    override def observe(remainingRequests: Option[Long],
                         remainingTokens: Option[Long],
                         resetSeconds: Option[Long],
                         retryAfter: Option[FiniteDuration]): Unit = ()
  }

  private val registry: ConcurrentHashMap[String, RateLimiter] = new ConcurrentHashMap[String, RateLimiter]

  /** Per-API-key shared limiter. The key is opaque to the framework —
   * apps typically pass the API key itself, or `s"$provider:$apiKey"`
   * for cross-provider keying. Two provider instances using the same
   * key share one limiter. */
  def forKey(key: String, config: RateLimiterConfig = RateLimiterConfig()): RateLimiter =
    registry.computeIfAbsent(key, _ => default(config))
}

private final class ProportionalRateLimiter(config: RateLimiterConfig) extends RateLimiter {

  /** Most-recent observed (remaining, capacity) ratio per kind. Two
   * dimensions matter for OpenAI / Anthropic: requests-per-minute and
   * tokens-per-minute. We track each separately and pick the tighter
   * floor for `acquire`'s sleep decision. */
  private val state: AtomicReference[State] = new AtomicReference(State.empty)

  override def acquire: Task[Unit] = {
    val s = state.get()
    val now = System.currentTimeMillis()
    s.retryUntil match {
      case Some(deadline) if deadline > now =>
        // Provider explicitly told us when to retry — honor it.
        Task.sleep(FiniteDuration(deadline - now, MILLISECONDS))
      case _ =>
        val tightest = List(s.requestRatio, s.tokenRatio).flatten.minOption.getOrElse(1.0)
        if (tightest <= config.hardFloor) Task.sleep(config.hardSleep)
        else if (tightest <= config.softFloor) Task.sleep(config.softSleep)
        else Task.unit
    }
  }

  override def observe(remainingRequests: Option[Long],
                       remainingTokens: Option[Long],
                       resetSeconds: Option[Long],
                       retryAfter: Option[FiniteDuration]): Unit = {
    state.updateAndGet { prev =>
      val nextRequest = remainingRequests
        .map(r => ratio(r.toDouble, prev.requestCapacity))
        .orElse(prev.requestRatio)
      val nextToken = remainingTokens
        .map(t => ratio(t.toDouble, prev.tokenCapacity))
        .orElse(prev.tokenRatio)
      val deadline = retryAfter.map(d => System.currentTimeMillis() + d.toMillis)
      // Track the largest remaining we've seen as our running
      // "capacity" estimate — providers don't always send `limit`
      // headers, but the post-throttle peak is a stable lower bound.
      val newRequestCap = remainingRequests.map(r => math.max(prev.requestCapacity, r.toDouble))
        .getOrElse(prev.requestCapacity)
      val newTokenCap = remainingTokens.map(t => math.max(prev.tokenCapacity, t.toDouble))
        .getOrElse(prev.tokenCapacity)
      State(
        requestRatio   = nextRequest,
        tokenRatio     = nextToken,
        retryUntil     = deadline,
        requestCapacity = newRequestCap,
        tokenCapacity   = newTokenCap
      )
    }
    ()
  }

  private def ratio(remaining: Double, capacity: Double): Double =
    if (capacity <= 0.0) 1.0 else math.max(0.0, math.min(1.0, remaining / capacity))
}

private object State {
  val empty: State = State(None, None, None, 0.0, 0.0)
}

private case class State(requestRatio: Option[Double],
                         tokenRatio: Option[Double],
                         retryUntil: Option[Long],
                         requestCapacity: Double,
                         tokenCapacity: Double)
