package spec

import fabric.*
import fabric.define.{Constraints, DefType, Definition, Format}
import fabric.rw.*
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import sigil.tool.ToolInputValidator

/**
 * Direct coverage for the post-decode constraint validator.
 * Focused on the constraint kinds Sigil's tool inputs actually use
 * today (`pattern`); the other constraint kinds are exercised via
 * synthetic Definitions to keep the test independent of any specific
 * tool's annotations.
 */
class ToolInputValidatorSpec extends AnyWordSpec with Matchers {

  private def stringDefWith(c: Constraints): Definition =
    Definition(DefType.Str, constraints = c)

  "ToolInputValidator" should {

    "pass when no constraints are set" in {
      val d = Definition(DefType.Str)
      ToolInputValidator.validate(str("anything"), d) shouldBe Nil
    }

    "report a pattern violation with the field path" in {
      val obj = Definition(DefType.Obj(Map(
        "keywords" -> stringDefWith(Constraints(pattern = Some("""^[a-z]+$""")))
      )))
      val json = fabric.obj("keywords" -> str("UPPERCASE"))
      val violations = ToolInputValidator.validate(json, obj)
      violations should have size 1
      violations.head should include("keywords")
      violations.head should include("pattern")
    }

    "pass a value that matches the pattern" in {
      val obj = Definition(DefType.Obj(Map(
        "keywords" -> stringDefWith(Constraints(pattern = Some("""^[a-z]+$""")))
      )))
      ToolInputValidator.validate(fabric.obj("keywords" -> str("alllower")), obj) shouldBe Nil
    }

    "enforce minLength / maxLength" in {
      val d = stringDefWith(Constraints(minLength = Some(3), maxLength = Some(5)))
      ToolInputValidator.validate(str("ab"), d).head should include("minLength")
      ToolInputValidator.validate(str("abcdef"), d).head should include("maxLength")
      ToolInputValidator.validate(str("abcd"), d) shouldBe Nil
    }

    "enforce numeric minimum / maximum" in {
      val d = Definition(DefType.Int, constraints = Constraints(minimum = Some(0), maximum = Some(10)))
      ToolInputValidator.validate(num(-1), d).head should include("minimum")
      ToolInputValidator.validate(num(11), d).head should include("maximum")
      ToolInputValidator.validate(num(5), d) shouldBe Nil
    }

    "recurse into arrays and report path with index" in {
      val arrDef = Definition(DefType.Arr(stringDefWith(Constraints(pattern = Some("""^x"""))))
      )
      val violations = ToolInputValidator.validate(arr(str("xa"), str("yb"), str("xc")), arrDef)
      violations should have size 1
      violations.head should include("[1]")
    }

    "skip null values for Opt-typed fields" in {
      val d = Definition(DefType.Obj(Map(
        "maybe" -> Definition(DefType.Opt(stringDefWith(Constraints(pattern = Some("""^x""")))))
      )))
      ToolInputValidator.validate(fabric.obj("maybe" -> Null), d) shouldBe Nil
    }

    "still validate Opt-typed fields when a value is present" in {
      val d = Definition(DefType.Obj(Map(
        "maybe" -> Definition(DefType.Opt(stringDefWith(Constraints(pattern = Some("""^x""")))))
      )))
      val violations = ToolInputValidator.validate(fabric.obj("maybe" -> str("nope")), d)
      violations should have size 1
      violations.head should include("maybe")
    }
  }
}
