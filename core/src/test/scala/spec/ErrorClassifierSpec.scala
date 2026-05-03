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
