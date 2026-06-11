package sigil

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import spec.TestSigil

/**
 * Sigil #385 — the cooperative progress checkpoint escalates from a
 * non-terminal nudge to a TERMINAL forced synthesis once the no-progress
 * streak persists past `hardNoProgressLimit`. This catches a varied-but-
 * unproductive loop (reading 40 distinct files without acting) that evades
 * the byte-identical hard-stall detector. Lives in package `sigil` to reach
 * the `private[sigil]` escalation seam.
 */
class NoProgressEscalationSpec extends AnyWordSpec with Matchers {

  "Sigil.terminalOnPersistentNoProgress (sigil #385)" should {
    "stay cooperative below hardNoProgressLimit and escalate at/above it" in {
      // Default hardNoProgressLimit = 4.
      TestSigil.terminalOnPersistentNoProgress(0) shouldBe false
      TestSigil.terminalOnPersistentNoProgress(3) shouldBe false
      TestSigil.terminalOnPersistentNoProgress(4) shouldBe true
      TestSigil.terminalOnPersistentNoProgress(9) shouldBe true
    }
  }
}
