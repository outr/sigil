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

  // Sleep durations are bumped to a value that's clearly distinguishable
  // from "no sleep" even on a JIT-warming CI runner. Earlier the config
  // used 50ms / 200ms; CI under load consistently took 250ms+ for
  // `Task.unit` alone, so the < 50ms "return immediately" assertions
  // failed without representing a real bug. With softSleep = 800ms, the
  // "no sleep" threshold has comfortable CI-tolerant headroom (< 400ms)
  // and the "did sleep" assertions stay well within their sleep window.
  private val cfg = RateLimiterConfig(
    softFloor = 0.5,
    softSleep = 800.millis,
    hardFloor = 0.1,
    hardSleep = 1600.millis
  )
  private val noSleepThresholdMs: Long = 400L

  "RateLimiter.NoOp" should {
    "acquire return without sleeping" in {
      val before = System.nanoTime()
      RateLimiter.NoOp.acquire.map { _ =>
        val elapsedMs = (System.nanoTime() - before) / 1_000_000L
        elapsedMs should be < noSleepThresholdMs
      }
    }
  }

  "RateLimiter.default" should {
    "acquire return without sleeping when no observations have happened" in {
      val limiter = RateLimiter.default(cfg)
      val before = System.nanoTime()
      limiter.acquire.map { _ =>
        val elapsedMs = (System.nanoTime() - before) / 1_000_000L
        elapsedMs should be < noSleepThresholdMs
      }
    }

    "acquire return without sleeping when remaining is well above the soft floor" in {
      val limiter = RateLimiter.default(cfg)
      // Capacity establishes itself from the first observation; subsequent
      // ratios are relative to that running max.
      limiter.observe(remainingRequests = Some(100), remainingTokens = Some(10000), resetSeconds = None, retryAfter = None)
      // Re-observe with healthy headroom — same as capacity, ratio = 1.0.
      limiter.observe(remainingRequests = Some(100), remainingTokens = Some(10000), resetSeconds = None, retryAfter = None)
      val before = System.nanoTime()
      limiter.acquire.map { _ =>
        val elapsedMs = (System.nanoTime() - before) / 1_000_000L
        elapsedMs should be < noSleepThresholdMs
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
        elapsedMs should be >= 700L  // softSleep - small slack
        elapsedMs should be < 1600L  // < hardSleep — we didn't drop to the hard floor
      }
    }

    "acquire sleep hardSleep when the bucket falls below the hard floor" in {
      val limiter = RateLimiter.default(cfg)
      limiter.observe(remainingRequests = Some(100), remainingTokens = Some(100), resetSeconds = None, retryAfter = None)
      limiter.observe(remainingRequests = Some(5),   remainingTokens = Some(5),   resetSeconds = None, retryAfter = None)
      val before = System.nanoTime()
      limiter.acquire.map { _ =>
        val elapsedMs = (System.nanoTime() - before) / 1_000_000L
        elapsedMs should be >= 1500L  // hardSleep - small slack
      }
    }

    "honour an explicit retry-after deadline over the proportional rules" in {
      val limiter = RateLimiter.default(cfg)
      limiter.observe(remainingRequests = Some(100), remainingTokens = Some(100), resetSeconds = None, retryAfter = Some(600.millis))
      val before = System.nanoTime()
      limiter.acquire.map { _ =>
        val elapsedMs = (System.nanoTime() - before) / 1_000_000L
        elapsedMs should be >= 500L  // retry-after - small slack
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
