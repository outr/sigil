package spec

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import sigil.tool.core.RecordConsentTool

/**
 * Sigil #344 — `record_consent` is REACTIVE: the agent calls it only when a
 * tool it tried to use was refused pending consent, never speculatively and
 * never just because the user picked an action from `respond_options` (the
 * selection IS the authorization; a non-gated tool runs directly). The old
 * description + its `start_metals` example actively taught the gratuitous
 * pre-consent pattern. These deterministic prose assertions lock the
 * corrected framing so a future edit can't silently regress it.
 */
class RecordConsentDescriptionSpec extends AnyWordSpec with Matchers {

  "RecordConsentTool.description" should {
    "frame consent as reactive to a refusal, not a courtesy" in {
      val d = RecordConsentTool.description
      d should include("REACTIVE")
      d should include("REFUSED")
    }

    "explicitly tell the agent a respond_options selection is NOT a reason to pre-consent" in {
      val d = RecordConsentTool.description
      d should include("respond_options")
      d should include("selection IS the authorization")
    }
  }

  "RecordConsentTool.examples" should {
    "never model pre-consent off a setup-options selection (no start_metals)" in {
      val rendered = RecordConsentTool.examples.map(e => e.description + " " + e.input.toString).mkString("\n")
      rendered should not include "start_metals"
    }

    "frame every example as a reaction to a consent refusal" in {
      // Both shipped examples react to `load_claude_state` being refused
      // pending consent — the approved and declined branches.
      RecordConsentTool.examples.map(_.description).foreach { d =>
        d.toLowerCase should include("refused pending consent")
      }
    }
  }
}
