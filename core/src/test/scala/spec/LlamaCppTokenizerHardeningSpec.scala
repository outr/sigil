package spec

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import sigil.provider.llamacpp.LlamaCppTokenizer
import sigil.tokenize.{HeuristicTokenizer, Tokenizer}
import spice.net.URL

import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.duration.*

/**
 * Coverage for sigil bug #54 — `LlamaCppTokenizer` hardening so a
 * stalled `/tokenize` backend can't block fiber threads
 * indefinitely or compound into a wall of `.sync()` waiters.
 *
 * Three guarantees verified here:
 *   1. **Cache** — repeated `count(text)` for the same string
 *      returns the cached value without hitting the fallback
 *      tokenizer's counter.
 *   2. **Circuit breaker** — after `breakerThreshold` failures the
 *      tokenizer short-circuits to fallback for `breakerCooldown`,
 *      avoiding stacked timeout waits.
 *   3. **Fallback parity on cold path** — when the breaker is open
 *      the fallback's count is what comes back.
 *
 * The HTTP path itself isn't exercised here — covered indirectly
 * by `LlamaCpp*Spec` live tests against the real backend. This
 * spec is unit-level: it points the tokenizer at an unreachable
 * URL so every remote attempt fails fast (within the
 * `requestTimeout`) and the breaker / cache logic can be observed.
 */
class LlamaCppTokenizerHardeningSpec extends AnyWordSpec with Matchers {

  /** Counts how many times the fallback was consulted; lets us
    * assert "cache hit" without instrumenting the tokenizer. */
  private class CountingFallback extends Tokenizer {
    val invocations: AtomicInteger = new AtomicInteger(0)
    override def count(text: String): Int = {
      invocations.incrementAndGet()
      HeuristicTokenizer.count(text)
    }
  }

  // Unreachable port — every /tokenize call fails fast. The tokenizer
  // catches the failure, increments its breaker counter, and falls
  // through to the heuristic.
  private val unreachable: URL = URL.parse("http://127.0.0.1:1") // port 1 = guaranteed-closed

  "LlamaCppTokenizer cache" should {

    "deduplicate counts for repeated identical text" in {
      val fallback = new CountingFallback
      val tok = LlamaCppTokenizer(
        baseUrl = unreachable,
        fallback = fallback,
        requestTimeout = 200.millis,
        cacheSize = 64,
        breakerThreshold = 1000  // disable breaker for this test
      )
      val n1 = tok.count("hello world")
      val before = fallback.invocations.get()
      val n2 = tok.count("hello world")
      val n3 = tok.count("hello world")
      n1 shouldBe n2
      n2 shouldBe n3
      // Subsequent calls must not consult the fallback again.
      fallback.invocations.get() shouldBe before
    }

    "evict the eldest entry when cache fills" in {
      val fallback = new CountingFallback
      val tok = LlamaCppTokenizer(
        baseUrl = unreachable,
        fallback = fallback,
        requestTimeout = 200.millis,
        cacheSize = 2,
        breakerThreshold = 1000
      )
      tok.count("a")
      tok.count("b")
      tok.count("c") // forces eviction of "a"
      val before = fallback.invocations.get()
      tok.count("a") // re-fetched (evicted)
      fallback.invocations.get() shouldBe (before + 1)
    }

    "skip the cache + remote path entirely for empty strings" in {
      val fallback = new CountingFallback
      val tok = LlamaCppTokenizer(unreachable, fallback, requestTimeout = 200.millis)
      tok.count("") shouldBe 0
      fallback.invocations.get() shouldBe 0
    }
  }

  "LlamaCppTokenizer circuit breaker" should {

    "trip after breakerThreshold consecutive failures and short-circuit subsequent calls to fallback" in {
      val fallback = new CountingFallback
      val tok = LlamaCppTokenizer(
        baseUrl = unreachable,
        fallback = fallback,
        requestTimeout = 200.millis,
        cacheSize = 1024,
        breakerThreshold = 2,
        breakerCooldown = 30.seconds
      )
      // First two failures consult fallback once each (count() flow).
      tok.count("trip-1")
      tok.count("trip-2")
      // Breaker now open. Subsequent unique texts go straight to
      // fallback without an HTTP attempt — but they DO still pass
      // through fallback (one invocation per unique text).
      val priorInvocations = fallback.invocations.get()
      val before = System.currentTimeMillis()
      tok.count("post-trip-1")
      val elapsed = System.currentTimeMillis() - before
      // Critically, the call must not block on the unreachable URL's
      // timeout — circuit-breaker fast path should be near-zero.
      elapsed should be < 100L
      fallback.invocations.get() shouldBe (priorInvocations + 1)
    }
  }
}
