package spec

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import sigil.provider.llamacpp.LlamaCppTokenizer
import sigil.tokenize.Tokenizer
import spice.net.url

/**
 * Coverage for sigil bug #45 — `LlamaCppProvider` overrides
 * `tokenizer` with a backend-call tokenizer. Unit-test scope: assert
 * the fallback path on transient failures (no live llama.cpp
 * server). Live `POST /tokenize` accuracy is exercised separately by
 * the LlamaCpp* live specs that opt in when the backend is
 * reachable.
 */
class LlamaCppTokenizerSpec extends AnyWordSpec with Matchers {

  // Sentinel fallback so the test asserts the fallback path was
  // chosen (vs. the heuristic happening to return the same number).
  case object SentinelFallback extends Tokenizer {
    override def count(text: String): Int = 999_999
  }

  "LlamaCppTokenizer" should {
    "fall back to the supplied fallback tokenizer when /tokenize is unreachable" in {
      // Use a definitely-unreachable port to force the fallback path.
      val tok = LlamaCppTokenizer(
        baseUrl = url"http://127.0.0.1:1",
        fallback = SentinelFallback
      )
      tok.count("any text") shouldBe 999_999
    }

    "default fallback to HeuristicTokenizer when no override supplied" in {
      val tok = LlamaCppTokenizer(baseUrl = url"http://127.0.0.1:1")
      // 28 chars / heuristic 3.5 chars/token ≈ 8.
      tok.count("twenty eight chars long blob") should be > 0
    }
  }
}
