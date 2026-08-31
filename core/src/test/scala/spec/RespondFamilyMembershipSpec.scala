package spec

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import sigil.tool.ToolName
import sigil.tool.core.{CoreTools, NoResponseTool, RespondCardTool, RespondCardsTool, RespondFamilyTool, RespondOptionsTool, RespondTool}

/**
 * RespondFamilyTool's companion is the ONE membership source for the
 * respond family — the orchestrator's internal-stamping / terminal
 * checks, the silent-turn detector, and provider heuristics all
 * consult it. A card respond IS the agent speaking, so respond_card /
 * respond_cards are members (the old string set omitted them).
 */
class RespondFamilyMembershipSpec extends AnyWordSpec with Matchers {

  "RespondFamilyTool membership" should {

    "cover all five respond-family tools" in {
      RespondFamilyTool.names shouldBe Set(
        RespondTool.name,
        RespondOptionsTool.name,
        RespondCardTool.name,
        RespondCardsTool.name,
        NoResponseTool.name
      )
    }

    "include the card responds the old terminal-tool string set missed" in {
      RespondFamilyTool.contains(ToolName("respond_card")) shouldBe true
      RespondFamilyTool.containsRaw("respond_cards") shouldBe true
    }

    "be the same source CoreTools.atomicContentToolNames serves" in {
      CoreTools.atomicContentToolNames shouldBe RespondFamilyTool.names
    }

    "answer membership for instances via the trait" in {
      RespondFamilyTool.isMember(RespondCardTool) shouldBe true
      RespondFamilyTool.isMember(sigil.tool.core.FindCapabilityTool) shouldBe false
    }

    "not match non-family tools by name" in {
      RespondFamilyTool.containsRaw("find_capability") shouldBe false
      RespondFamilyTool.containsRaw("stop") shouldBe false
    }
  }
}
