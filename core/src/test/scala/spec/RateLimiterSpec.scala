package spec

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.AsyncTaskSpec
import sigil.provider.{RateLimiter, RateLimiterConfig}

import scala.concurrent.duration.*

/**
 * Coverage for the proactive [[RateLimiter]] added by the provider-
 * robustness bundle. Two paths matter:
 *
 *   1. `acquire` returns immediately when no headroom data has been
 *      observed yet OR when the bucket is comfortably above the
 *      configured `softFloor`.
 *   2. `acquire` sleeps the configured duration when the bucket falls
 *      under `softFloor` (or `hardFloor`), and honours an explicit
 *      `retry-after` deadline when the upstream provided one.
 *
 * The framework's per-API-key registry returns the same `RateLimiter`
 * for the same key — verified to ensure two providers sharing one
 * upstream account share one limiter (the rate limit is per-key, not
 * per-instance).
 */
class RateLimiterSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {

  // Sub-millisecond floors so the spec stays cheap.
  private val cfg = RateLimiterConfig(
    softFloor = 0.5,
    softSleep = 50.millis,
    hardFloor = 0.1,
    hardSleep = 200.millis
  )

  "RateLimiter.NoOp" should {
    "acquire return immediately" in {
      val before = System.nanoTime()
      RateLimiter.NoOp.acquire.map { _ =>
        val elapsedMs = (System.nanoTime() - before) / 1_000_000L
        elapsedMs should be < 50L
      }
    }
  }

  "RateLimiter.default" should {
    "acquire return immediately when no observations have happened" in {
      val limiter = RateLimiter.default(cfg)
      val before = System.nanoTime()
      limiter.acquire.map { _ =>
        val elapsedMs = (System.nanoTime() - before) / 1_000_000L
        elapsedMs should be < 50L
      }
    }

    "acquire return immediately when remaining is well above the soft floor" in {
      val limiter = RateLimiter.default(cfg)
      // Capacity establishes itself from the first observation; subsequent
      // ratios are relative to that running max.
      limiter.observe(remainingRequests = Some(100), remainingTokens = Some(10000), resetSeconds = None, retryAfter = None)
      // Re-observe with healthy headroom — same as capacity, ratio = 1.0.
      limiter.observe(remainingRequests = Some(100), remainingTokens = Some(10000), resetSeconds = None, retryAfter = None)
      val before = System.nanoTime()
      limiter.acquire.map { _ =>
        val elapsedMs = (System.nanoTime() - before) / 1_000_000L
        elapsedMs should be < 50L
      }
    }

    "acquire sleep softSleep when the bucket falls below the soft floor" in {
      val limiter = RateLimiter.default(cfg)
      // Establish 100 as capacity, then drop to 30 (= 0.3, below softFloor 0.5
      // but above hardFloor 0.1).
      limiter.observe(remainingRequests = Some(100), remainingTokens = Some(100), resetSeconds = None, retryAfter = None)
      limiter.observe(remainingRequests = Some(30),  remainingTokens = Some(30),  resetSeconds = None, retryAfter = None)
      val before = System.nanoTime()
      limiter.acquire.map { _ =>
        val elapsedMs = (System.nanoTime() - before) / 1_000_000L
        elapsedMs should be >= 40L
        elapsedMs should be < 200L
      }
    }

    "acquire sleep hardSleep when the bucket falls below the hard floor" in {
      val limiter = RateLimiter.default(cfg)
      limiter.observe(remainingRequests = Some(100), remainingTokens = Some(100), resetSeconds = None, retryAfter = None)
      limiter.observe(remainingRequests = Some(5),   remainingTokens = Some(5),   resetSeconds = None, retryAfter = None)
      val before = System.nanoTime()
      limiter.acquire.map { _ =>
        val elapsedMs = (System.nanoTime() - before) / 1_000_000L
        elapsedMs should be >= 180L
      }
    }

    "honour an explicit retry-after deadline over the proportional rules" in {
      val limiter = RateLimiter.default(cfg)
      limiter.observe(remainingRequests = Some(100), remainingTokens = Some(100), resetSeconds = None, retryAfter = Some(150.millis))
      val before = System.nanoTime()
      limiter.acquire.map { _ =>
        val elapsedMs = (System.nanoTime() - before) / 1_000_000L
        elapsedMs should be >= 130L
      }
    }
  }

  "RateLimiter.forKey" should {
    "return the same instance for the same key" in {
      val a = RateLimiter.forKey("test-key-1")
      val b = RateLimiter.forKey("test-key-1")
      (a eq b) shouldBe true
      // Use the result so the Future has a yielded value
      rapid.Task.unit.map(_ => succeed)
    }

    "return distinct instances for distinct keys" in {
      val a = RateLimiter.forKey("test-key-distinct-a")
      val b = RateLimiter.forKey("test-key-distinct-b")
      (a eq b) shouldBe false
      rapid.Task.unit.map(_ => succeed)
    }
  }
}
