package spec

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import sigil.{AgentRunawayException, ForcedSynthesisReason, Sigil}

/**
 * Sigil #376 — the runaway/stall terminal (iteration cap or
 * progress-checkpoint stall whose forced-synthesis recovery still
 * failed) is published as a RECOVERABLE failure, so a follow-up user
 * message re-engages the agent instead of dead-ending the conversation.
 * A genuine crash (a tool throwing, a projection blowing up) stays
 * non-recoverable. This pins the discriminator `publishFailureMessage`
 * uses for `MessageDisposition.Failure(recoverable = …)`.
 */
class RunawayRecoverableDispositionSpec extends AnyWordSpec with Matchers {

  "Sigil.isStallFailure (sigil #376)" should {

    "treat an AgentRunawayException as a recoverable stall" in {
      Sigil.isStallFailure(new AgentRunawayException("cap hit", ForcedSynthesisReason.CapHit)) shouldBe true
      Sigil.isStallFailure(new AgentRunawayException("stalled", ForcedSynthesisReason.StallIntervention)) shouldBe true
    }

    "treat a genuine crash as non-recoverable" in {
      Sigil.isStallFailure(new RuntimeException("tool blew up")) shouldBe false
      Sigil.isStallFailure(new NullPointerException()) shouldBe false
    }
  }
}
