package spec

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import sigil.tokenize.{HeuristicTokenizer, JtokkitTokenizer, Tokenizer}

/**
 * Regression coverage for bug #76 — `sigil-core`'s declared `jtokkit`
 * dependency doesn't always resolve transitively into a downstream
 * consumer's classpath; when it's missing, `OpenAIProvider.tokenizer`
 * used to throw `NoClassDefFoundError` at the first agent turn,
 * which the agent loop swallowed silently.
 *
 * The fix has two halves:
 *   1. `sigil-all` re-declares `jtokkit` in its own `libraryDependencies`
 *      so the umbrella POM lists it at top level for every resolver.
 *   2. `JtokkitTokenizer.OpenAIChatGpt` / `.OpenAIO200k` probe for
 *      jtokkit availability at object init; on absence, log a one-time
 *      WARN and return [[HeuristicTokenizer]] instead of throwing.
 *
 * From within the `core` test classpath jtokkit IS available (declared
 * in `sigil-core`'s direct deps), so the fallback path doesn't fire
 * here — but we can still verify the probe returns true and the
 * `OpenAIChatGpt` instance counts a known string with non-zero
 * accuracy. The tests would catch a regression where the dep is
 * accidentally removed.
 */
class JtokkitFallbackSpec extends AnyWordSpec with Matchers {

  "JtokkitTokenizer.available" should {
    "report true when jtokkit is on the classpath" in {
      JtokkitTokenizer.available shouldBe true
    }
  }

  "JtokkitTokenizer.OpenAIChatGpt" should {
    "count tokens for a known string with a positive integer (jtokkit-backed when available)" in {
      val n = JtokkitTokenizer.OpenAIChatGpt.count("hello world")
      n should be > 0
    }

    "be a Tokenizer regardless of whether jtokkit is on the classpath" in {
      JtokkitTokenizer.OpenAIChatGpt shouldBe a [Tokenizer]
    }
  }

  "JtokkitTokenizer.OpenAIO200k" should {
    "count tokens for a known string with a positive integer" in {
      val n = JtokkitTokenizer.OpenAIO200k.count("hello world")
      n should be > 0
    }
  }

  "HeuristicTokenizer" should {
    "remain available as a fallback when jtokkit is missing" in {
      HeuristicTokenizer.count("hello world") should be > 0
    }
  }

  "JtokkitTokenizer.selectTokenizer (bug #76 fallback)" should {
    "return the encoding factory's tokenizer when probe = true (jtokkit available path)" in {
      // We can't observe `selectTokenizer` directly (it's package-private),
      // but the production constants `OpenAIChatGpt` / `OpenAIO200k` exercise
      // the probe = true path because jtokkit IS on the test classpath.
      // Both should be JtokkitTokenizer instances with non-zero counts.
      JtokkitTokenizer.OpenAIChatGpt.getClass.getSimpleName shouldBe "JtokkitTokenizer"
      JtokkitTokenizer.OpenAIO200k.getClass.getSimpleName shouldBe "JtokkitTokenizer"
    }

    "return HeuristicTokenizer when probe = false (jtokkit missing path) — deterministic test of the fallback branch" in {
      // Use the package-private decision helper to drive the false branch
      // without actually removing jtokkit from the classpath. This is the
      // only way to lock in bug #76's fix — without this assertion, the
      // fallback branch would be dead code that no test ever executes.
      val factory: () => sigil.tokenize.Tokenizer = () => fail(
        "encoding factory must NOT be invoked when probe = false"
      )
      val result = sigil.tokenize.JtokkitTokenizer.selectTokenizer(
        probe = false,
        encodingFactory = factory,
        slotName = "test-fallback"
      )
      result shouldBe HeuristicTokenizer
    }

    "use the encoding factory when probe = true" in {
      // Drive the true branch with a stub factory; assert factory was invoked
      // and its return value is what selectTokenizer returns.
      val sentinel: sigil.tokenize.Tokenizer = new sigil.tokenize.Tokenizer {
        override def count(text: String): Int = 42
      }
      var factoryInvoked = false
      val result = sigil.tokenize.JtokkitTokenizer.selectTokenizer(
        probe = true,
        encodingFactory = () => { factoryInvoked = true; sentinel },
        slotName = "test-real"
      )
      factoryInvoked shouldBe true
      result shouldBe sentinel
    }
  }
}
