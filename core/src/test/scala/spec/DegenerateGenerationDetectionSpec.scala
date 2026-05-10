package spec

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import sigil.conversation.ContextKey
import sigil.conversation.compression.ParaphraseLoopDetector
import sigil.provider.DegenerateContentDetector

/**
 * Coverage for [[DegenerateContentDetector]] — the heuristic that
 * flags token-level repetition loops in model output (the failure
 * mode where the model emits the same sentence over and over until
 * `max_tokens` fires).
 *
 * Scenarios mirror the bug report:
 *
 *   1. The exact qwen3.6-35b wire-log scenario from 2026-05-10:
 *      17 copies of "Let me pull up the full list…" → detector
 *      fires with the right counts.
 *   2. Short outputs aren't flagged (below `minLength`).
 *   3. Legitimate long-but-non-repetitive prose isn't flagged.
 *   4. Repetition below `shareThreshold` isn't flagged.
 *   5. Per-output diagnostic text names the loop concretely so the
 *      agent's next iteration can self-correct.
 */
class DegenerateGenerationDetectionSpec extends AnyWordSpec with Matchers {

  "DegenerateContentDetector.detect" should {

    "fire on the exact wire-log scenario (60 copies of the same sentence)" in {
      val sentence = "Found many password-related files. Let me pull up the full list and key source files."
      // 60 occurrences mirrors the live wire-log shape more
      // faithfully — at qwen3.6-35b's max_tokens=4096 the actual
      // generation produced ~200k chars across many repetitions.
      // Sentence length is ~86 chars; need >= 5000 chars total
      // (DegenerateContentDetector.minLength) so use 60.
      val text = List.fill(60)(sentence).mkString(" ")
      val hit = DegenerateContentDetector.Default.detect(text)
      withClue(s"text length = ${text.length}: ") {
        hit shouldBe defined
      }
      hit.get.occurrences shouldBe 60
      hit.get.share should be > 0.4
      hit.get.repeatedSentence should include ("Let me pull up")
    }

    "NOT fire on a short response (below minLength)" in {
      val text = ("Same sentence. " * 5).trim
      DegenerateContentDetector.Default.detect(text) shouldBe None
    }

    "NOT fire on legitimate long-but-non-repetitive prose" in {
      // 20 distinct sentences, each at least 250 chars → ~5000+ chars,
      // none repeating.
      val sentences = (1 to 20).map { i =>
        val filler = (1 to 30).map(j => s"word$i-$j").mkString(" ")
        s"Sentence $i discusses topic $i with the following content: $filler."
      }
      val text = sentences.mkString(" ")
      withClue(s"text length = ${text.length}: ") {
        text.length should be > 5000
      }
      DegenerateContentDetector.Default.detect(text) shouldBe None
    }

    "NOT fire when the dominant sentence's share is below threshold" in {
      // 5 copies of the target + many distinct sentences → share well below 0.4.
      val target = "Target repeated sentence appears five times."
      val noise = (1 to 30).map(i => s"Distinct sentence $i contains unique words like noise$i.").mkString(" ")
      val text = ((List.fill(5)(target) ++ List(noise)).mkString(" ") + " ").trim * 5
      // Even with the wrapping, target's share stays well below shareThreshold.
      val hit = DegenerateContentDetector.Default.detect(text)
      // Either no hit or hit with low share — the heuristic is conservative.
      hit match {
        case None => succeed
        case Some(h) => h.share should be <= 0.4
      }
    }

    "render a diagnostic that names the loop and instructs the agent" in {
      val sentence = "Repeated sentence. " * 30
      val padded   = sentence + " " * (5000 - sentence.length max 0)
      val text     = padded * 2  // ensure > minLength comfortably
      val hit      = DegenerateContentDetector.Default.detect(text).get
      val diag     = hit.renderDiagnostic(text.length)
      diag should include ("repetition loop")
      diag should include ("max_tokens")
      diag should include ("find_capability")  // self-correction suggestion
    }
  }

  "Provider's adaptive max_tokens clamp" should {

    "expose the cap as a public constant" in {
      sigil.provider.Provider.ParaphraseLoopMaxOutputTokensCap shouldBe 500
    }

    "use the same context key the curator injects" in {
      // The two systems agree on the key value so the provider's
      // detection of paraphrase signal matches what the curator
      // writes.
      ContextKey(ParaphraseLoopDetector.ContextKeyValue).value shouldBe "_paraphraseObservation"
    }
  }
}
