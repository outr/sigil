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
      r should not include ("TOOLS\n-")  // the discovery TOOLS section
      r should not include ("find_capability")
      r should include ("SAFETY")
      r should include ("BEHAVIOR")
    }

    "still include the trailing toolsTrailer recap" in {
      val r = Instructions().renderWithoutTools
      r should include ("REMINDER:")
      r should include ("Plain text")
    }
  }

  "Instructions toolsTrailer" should {
    "render the recap LAST in the system prompt by default" in {
      val r = Instructions().render
      r should include ("REMINDER:")
      r should include ("Plain text")
      // Tool-neutral: naming a specific tool family biases the model
      // away from the others.
      r should not include ("respond-family")
      val recapIdx = r.indexOf("REMINDER:")
      val toolsIdx = r.indexOf("TOOLS")
      recapIdx should be > toolsIdx
      r.lastIndexOf("REMINDER:") should be > r.length - 600
    }

    "render LAST even when guidelines are present" in {
      val r = Instructions().withGuidelines("custom guideline 1", "custom guideline 2").render
      val recapIdx = r.indexOf("REMINDER:")
      val firstGuidelineIdx = r.indexOf("custom guideline 1")
      val secondGuidelineIdx = r.indexOf("custom guideline 2")
      // Guidelines come BEFORE the recap; recap is the absolute tail.
      firstGuidelineIdx should be > 0
      secondGuidelineIdx should be > firstGuidelineIdx
      recapIdx should be > secondGuidelineIdx
    }

    "be suppressible via toolsTrailer = \"\"" in {
      // Frontier-model apps that don't need the recap can opt out.
      val r = Instructions(toolsTrailer = "").render
      r should not include ("REMINDER:")
      r should not include ("plain text")
    }

    "carry through `withToolsTrailer`" in {
      val custom = "REMINDER: this is a custom trailer."
      val r = Instructions().withToolsTrailer(custom).render
      r should include ("custom trailer.")
      r should not include ("plain text")
    }
  }
}
