package spec

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Task}
import sigil.db.Model
import sigil.provider.anthropic.{Anthropic, AnthropicProvider}
import spice.http.Headers

/**
 * Regression for sigil bug #284 — [[AnthropicProvider]] sniffs the
 * `anthropic-ratelimit-input-tokens-limit` response header on every
 * call and writes it back to the [[sigil.db.Model]] record so #283's
 * pre-flight rate-limit guard fires on the next call. Without the
 * sniff, `Model.inputTokensPerMinute` stays `None` forever and the
 * guard skips, leaving the retry loop to burn the per-minute budget
 * on doomed 429-returning requests.
 */
class AnthropicRateLimitSniffSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  private val provider = AnthropicProvider(
    apiKey   = "synthetic-test-key",
    sigilRef = TestSigil
  )

  "AnthropicProvider.sniffRateLimitHeaders (sigil #284)" should {

    "populate Model.inputTokensPerMinute from the response header on first call" in {
      val modelId = Model.id("anthropic", "rl-sniff-first")
      TestSigil.testModel(modelId) // registers a synthetic record with inputTokensPerMinute = None
      TestSigil.cache.find(modelId).flatMap(_.inputTokensPerMinute) shouldBe None

      val headers = Headers.empty.withHeader(Anthropic.RateLimitInputTokensHeader, "450000")
      provider.sniffRateLimitHeaders(modelId, Task.pure(headers)).map { _ =>
        TestSigil.cache.find(modelId).flatMap(_.inputTokensPerMinute) shouldBe Some(450000L)
      }
    }

    "update Model.inputTokensPerMinute when the upstream's reported limit changes (plan-tier upgrade)" in {
      val modelId = Model.id("anthropic", "rl-sniff-upgrade")
      val seed = TestSigil.testModel(modelId).copy(inputTokensPerMinute = Some(100_000L))
      TestSigil.cache.merge(List(seed)).flatMap { _ =>
        val headers = Headers.empty.withHeader(Anthropic.RateLimitInputTokensHeader, "900000")
        provider.sniffRateLimitHeaders(modelId, Task.pure(headers)).map { _ =>
          TestSigil.cache.find(modelId).flatMap(_.inputTokensPerMinute) shouldBe Some(900_000L)
        }
      }
    }

    "no-op when the limit is unchanged (avoids gratuitous cache.merge calls per turn)" in {
      val modelId = Model.id("anthropic", "rl-sniff-stable")
      val seed = TestSigil.testModel(modelId).copy(inputTokensPerMinute = Some(450_000L))
      TestSigil.cache.merge(List(seed)).flatMap { _ =>
        val before = TestSigil.cache.find(modelId).get.modified
        val headers = Headers.empty.withHeader(Anthropic.RateLimitInputTokensHeader, "450000")
        provider.sniffRateLimitHeaders(modelId, Task.pure(headers)).map { _ =>
          // `modified` would have advanced if the record was rewritten;
          // staying stable proves the no-op path fired. (cache.merge
          // refreshes `modified` on every write.)
          TestSigil.cache.find(modelId).get.modified shouldBe before
        }
      }
    }

    "leave the record untouched when the header is absent" in {
      val modelId = Model.id("anthropic", "rl-sniff-absent")
      TestSigil.testModel(modelId) // inputTokensPerMinute starts None
      val headers = Headers.empty
      provider.sniffRateLimitHeaders(modelId, Task.pure(headers)).map { _ =>
        TestSigil.cache.find(modelId).flatMap(_.inputTokensPerMinute) shouldBe None
      }
    }

    "ignore a malformed header value rather than crashing the call" in {
      val modelId = Model.id("anthropic", "rl-sniff-malformed")
      TestSigil.testModel(modelId)
      val headers = Headers.empty.withHeader(Anthropic.RateLimitInputTokensHeader, "not-a-number")
      provider.sniffRateLimitHeaders(modelId, Task.pure(headers)).map { _ =>
        TestSigil.cache.find(modelId).flatMap(_.inputTokensPerMinute) shouldBe None
      }
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
