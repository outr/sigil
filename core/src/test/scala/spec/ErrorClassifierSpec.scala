package spec

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import sigil.provider.{ErrorClassification, ErrorClassifier}

/**
 * Coverage for the default [[ErrorClassifier]] heuristics and
 * [[ErrorClassifier.orElse]] composition. The default classifier's
 * string-matching covers HTTP-status / common-network signatures —
 * regressions there silently degrade fallback routing in
 * [[sigil.provider.ProviderStrategy]]. `orElse` is the public hook
 * apps wire their own provider-specific classifiers on top of, so
 * the composition semantics need to be locked in.
 */
class ErrorClassifierSpec extends AnyWordSpec with Matchers {

  private def classify(message: String): ErrorClassification =
    ErrorClassifier.Default.classify(new RuntimeException(message))

  "ErrorClassifier.Default" should {
    "treat 429 / rate-limit signatures as Retry" in {
      classify("HTTP 429 Too Many Requests") shouldBe ErrorClassification.Retry
      classify("rate limit reached for tokens") shouldBe ErrorClassification.Retry
    }

    "treat timeouts as Retry" in {
      classify("Read timed out after 60s") shouldBe ErrorClassification.Retry
      ErrorClassifier.Default.classify(
        new java.net.SocketTimeoutException("connect timed out")
      ) shouldBe ErrorClassification.Retry
    }

    "treat 5xx + connection-reset as Retry" in {
      classify("HTTP 503 Service Unavailable") shouldBe ErrorClassification.Retry
      classify("HTTP 502 Bad Gateway") shouldBe ErrorClassification.Retry
      classify("Connection reset by peer") shouldBe ErrorClassification.Retry
    }

    "treat auth failures as Fatal" in {
      classify("HTTP 401 Unauthorized") shouldBe ErrorClassification.Fatal
      classify("Invalid API key supplied") shouldBe ErrorClassification.Fatal
      classify("HTTP 403 Forbidden") shouldBe ErrorClassification.Fatal
    }

    "treat malformed-request 400s as Fatal" in {
      classify("HTTP 400 Bad Request: invalid model") shouldBe ErrorClassification.Fatal
    }

    "treat unknown errors as Fallthrough so the next candidate runs" in {
      classify("some weird upstream message") shouldBe ErrorClassification.Fallthrough
      classify("HTTP 404 not found") shouldBe ErrorClassification.Fallthrough
    }
  }

  "ErrorClassifier.Default — typed dispatch (Sigil audit H5)" should {
    "classify ProviderStreamException as Fallthrough by type, not message text" in {
      val ex = new sigil.provider.ProviderStreamException(
        providerKey = "test", code = 200, typ = "empty_budget_burn",
        message_ = "no transient keyword in this message"
      )
      ErrorClassifier.Default.classify(ex) shouldBe ErrorClassification.Fallthrough
    }

    "classify malformed_tool_args as Fallthrough so the strategy can route to another candidate" in {
      val ex = new sigil.provider.ProviderStreamException(
        providerKey = "test", code = 200, typ = "malformed_tool_args",
        message_ = "model emitted a JSON array"
      )
      ErrorClassifier.Default.classify(ex) shouldBe ErrorClassification.Fallthrough
    }

    "classify CapacityAcquireTimeoutException as Fallthrough (this candidate is saturated)" in {
      val ex = new sigil.provider.CapacityAcquireTimeoutException(
        maxConcurrent = 4, waited = scala.concurrent.duration.FiniteDuration(60, "s")
      )
      ErrorClassifier.Default.classify(ex) shouldBe ErrorClassification.Fallthrough
    }

    "classify RequestOverBudgetException as Fatal (no candidate-level mitigation)" in {
      val ex = new sigil.provider.RequestOverBudgetException(
        estimatedTokens = 100000, contextLength = 8192,
        modelId = lightdb.id.Id[sigil.db.Model]("test/model")
      )
      ErrorClassifier.Default.classify(ex) shouldBe ErrorClassification.Fatal
    }

    "classify AgentRunawayException as Fatal (cap reached, infinite retry would loop)" in {
      val ex = new sigil.AgentRunawayException("test/agent hit maxAgentIterations")
      ErrorClassifier.Default.classify(ex) shouldBe ErrorClassification.Fatal
    }
  }

  "ErrorClassifier#orElse" should {
    "let the left win on Retry / Fatal" in {
      val custom: ErrorClassifier = (_: Throwable) => ErrorClassification.Retry
      val composed = custom.orElse(ErrorClassifier.Default)
      composed.classify(new RuntimeException("HTTP 401 Unauthorized")) shouldBe ErrorClassification.Retry
    }

    "delegate to the right when the left says Fallthrough" in {
      val passthrough: ErrorClassifier = (_: Throwable) => ErrorClassification.Fallthrough
      val composed = passthrough.orElse(ErrorClassifier.Default)
      composed.classify(new RuntimeException("HTTP 429 Too Many Requests")) shouldBe ErrorClassification.Retry
      composed.classify(new RuntimeException("HTTP 401 Unauthorized")) shouldBe ErrorClassification.Fatal
    }

    "chain three classifiers left-to-right" in {
      val a: ErrorClassifier = t =>
        if (t.getMessage == "marker-a") ErrorClassification.Retry else ErrorClassification.Fallthrough
      val b: ErrorClassifier = t =>
        if (t.getMessage == "marker-b") ErrorClassification.Fatal else ErrorClassification.Fallthrough
      val composed = a.orElse(b).orElse(ErrorClassifier.Default)
      composed.classify(new RuntimeException("marker-a")) shouldBe ErrorClassification.Retry
      composed.classify(new RuntimeException("marker-b")) shouldBe ErrorClassification.Fatal
      composed.classify(new RuntimeException("HTTP 503")) shouldBe ErrorClassification.Retry
      composed.classify(new RuntimeException("totally novel error")) shouldBe ErrorClassification.Fallthrough
    }
  }
}
