package spec

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import sigil.orchestrator.ReasoningResidue

/**
 * Boundary cases for separating spoken prose from the reasoning a
 * backend mis-split into the same wire field. Exercised end-to-end by
 * [[PreambleProseAlongsideToolCallSpec]]; pinned here per shape because
 * each mis-split variant is a distinct string boundary and a live turn
 * per variant buys nothing.
 */
class ReasoningResidueSpec extends AnyWordSpec with Matchers {

  "Reasoning residue" should {

    "keep prose that carries no reasoning marker" in {
      ReasoningResidue.strip("Let me check both files first.") shouldBe "Let me check both files first."
      ReasoningResidue.spoken("Let me check both files first.") shouldBe true
    }

    "drop everything through a closing tag" in {
      // The llama.cpp / Qwen shape: the thinking tail and its close tag
      // arrive as assistant content ahead of the tool call.
      ReasoningResidue.strip("4\n</think>\n\n") shouldBe ""
      ReasoningResidue.spoken("4\n</think>\n\n") shouldBe false
    }

    "keep the prose that follows a closing tag" in {
      ReasoningResidue.strip("...so I'll grep.</think>\n\nChecking the callers now.") shouldBe
        "Checking the callers now."
    }

    "cut at the last closing tag when several leak" in {
      ReasoningResidue.strip("a</thinking>b</think>c") shouldBe "c"
    }

    "drop everything from an unmatched opening tag" in {
      ReasoningResidue.strip("Checking.<think>the user wants") shouldBe "Checking."
      ReasoningResidue.spoken("<think>the user wants") shouldBe false
    }

    "match tags regardless of case" in {
      ReasoningResidue.strip("done</THINK>Ready.") shouldBe "Ready."
    }
  }
}
