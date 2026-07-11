package spec

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import sigil.provider.ConversationMode
import sigil.tool.core.{NoResponseTool, RespondOptionsTool, RespondTool}

/**
 * Wording regression for the `respond` terminality messaging. The
 * respond family's shared wire headline was an unconditional
 * `**ENDS YOUR TURN.**` — which contradicted `respond`'s required
 * `endsTurn` flag and primed models (small local ones especially) to
 * fill `endsTurn = true` as "the value consistent with the tool I just
 * chose", even when their own content announced work they hadn't done.
 * Observed terminal shape: `respond {"content": "Searching…",
 * "endsTurn": true}` — turn settled at iteration 1, zero work done.
 *
 * Pins:
 *   - `respond`'s wire description states the CONDITIONAL truth (ends
 *     the turn only when `endsTurn = true`) and carries the
 *     contrapositive models keep missing (announced-but-undone work
 *     requires `endsTurn = false`).
 *   - The always-terminal siblings keep the unconditional headline —
 *     for them it is the truth.
 */
class RespondEndsTurnWordingSpec extends AnyWordSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  private def wire(tool: sigil.tool.Tool): String =
    tool.wireDescription(ConversationMode, TestSigil)

  "respond's wire description" should {

    "not open with the unconditional ENDS YOUR TURN headline" in {
      wire(RespondTool) should not startWith "**ENDS YOUR TURN.**"
    }

    "state the conditional terminality" in {
      val d = wire(RespondTool)
      d should include("only when `endsTurn` = true")
      d should include("you keep working")
    }

    "carry the announced-work contrapositive" in {
      val d = wire(RespondTool)
      d should include("what you DID, not what you are about to do")
      d should include("announces work you have not done yet")
    }
  }

  "the always-terminal siblings" should {

    "keep the unconditional headline" in {
      wire(RespondOptionsTool) should startWith("**ENDS YOUR TURN.**")
      wire(NoResponseTool) should startWith("**ENDS YOUR TURN.**")
    }
  }
}
