package spec

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import sigil.tool.ToolName

import scala.compiletime.testing.typeChecks

/**
 * ToolName's compile-time tier: a literal outside the authoring
 * grammar `[a-z][a-z0-9_]{0,63}` is a compile error, and a
 * non-constant argument cannot use the literal constructor at all.
 * Runtime names go through `parse`, validated against the wider
 * provider grammar `[a-zA-Z0-9_-]{1,64}`.
 */
class ToolNameGrammarSpec extends AnyWordSpec with Matchers {

  "ToolName literal construction" should {

    "compile for a snake_case literal" in {
      typeChecks("""sigil.tool.ToolName("good_name_2")""") shouldBe true
    }

    "reject an uppercase literal at compile time" in {
      typeChecks("""sigil.tool.ToolName("BadName")""") shouldBe false
    }

    "reject a dash literal at compile time" in {
      typeChecks("""sigil.tool.ToolName("bad-name")""") shouldBe false
    }

    "reject an underscore-leading literal at compile time (internal constructor territory)" in {
      typeChecks("""sigil.tool.ToolName("_synthetic")""") shouldBe false
    }

    "reject an over-64-char literal at compile time" in {
      typeChecks(
        """sigil.tool.ToolName("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")"""
      ) shouldBe false
    }

    "reject a non-constant argument at compile time" in {
      typeChecks("""{ val dynamic: String = "grep"; sigil.tool.ToolName(dynamic) }""") shouldBe false
    }
  }

  "ToolName.parse" should {

    "accept provider-grammar names literals cannot express" in {
      ToolName.parse("Metals-Find-Symbol").map(_.value) shouldBe Right("Metals-Find-Symbol")
      ToolName.parse("mixedCase_ok-2").isRight shouldBe true
    }

    "reject names outside the provider grammar with the grammar in the message" in {
      ToolName.parse("has space").left.toOption.get should include(ToolName.DynamicGrammar)
      ToolName.parse("").isLeft shouldBe true
      ToolName.parse("dot.name").isLeft shouldBe true
      ToolName.parse("a" * 65).isLeft shouldBe true
    }
  }
}
