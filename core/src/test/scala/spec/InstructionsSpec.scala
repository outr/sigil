package spec

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import sigil.provider.Instructions

/**
 * Defaults guard. The framework's `Instructions()` produces a system
 * prompt that includes safety, behavior, AND tool-discovery framing
 * out of the box — without this, agents respect `find_capability`'s
 * tool-description "CALL THIS FIRST" instruction inconsistently
 * because the assistant prior wins.
 */
class InstructionsSpec extends AnyWordSpec with Matchers {

  "Instructions()" should {
    val rendered = Instructions().render

    "include the SAFETY block" in {
      rendered should include ("SAFETY")
    }

    "include the BEHAVIOR block" in {
      rendered should include ("BEHAVIOR")
    }

    "include the TOOLS discovery block" in {
      rendered should include ("TOOLS")
      rendered should include ("find_capability")
      rendered should include ("discovered, not preloaded")
    }
  }

  "Instructions(tools = \"\")" should {
    "drop the TOOLS block when explicitly disabled" in {
      val rendered = Instructions(tools = "").render
      rendered should not include ("TOOLS\n-")
    }
  }

  "Instructions.autonomous()" should {
    "still include the TOOLS block (autonomy is about safety, not discovery)" in {
      Instructions.autonomous().render should include ("TOOLS")
    }
  }

  "Instructions.renderWithoutTools" should {
    "drop the TOOLS block but keep the rest" in {
      val r = Instructions().renderWithoutTools
      r should not include ("TOOLS")
      r should not include ("find_capability")
      r should include ("SAFETY")
      r should include ("BEHAVIOR")
    }
  }
}
