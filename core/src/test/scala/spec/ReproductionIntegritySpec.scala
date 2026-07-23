package spec

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import sigil.tool.ReproductionIntegrity
import sigil.tool.ReproductionIntegrity.{AlteredLine, DroppedLine}

class ReproductionIntegritySpec extends AnyWordSpec with Matchers {

  private val nothingEditable: String => Boolean = _ => false
  private val commentsEditable: String => Boolean = line => {
    val t = line.trim
    t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")
  }

  private val original =
    """object Sample {
      |  // explains the calculation
      |  // across two comment lines
      |  def calc(x: Int): Int = {
      |    val doubled = x * 2
      |    doubled + 1
      |  }
      |}""".stripMargin

  "ReproductionIntegrity.validate" should {

    "pass identical input and output" in {
      ReproductionIntegrity.validate(original, original, nothingEditable).ok shouldBe true
    }

    "pass a comment-only sweep that leaves every code line intact" in {
      val refactored =
        """object Sample {
          |  // rewritten explanation, one line now
          |  def calc(x: Int): Int = {
          |    val doubled = x * 2
          |    doubled + 1
          |  }
          |}""".stripMargin
      ReproductionIntegrity.validate(original, refactored, commentsEditable).ok shouldBe true
    }

    "flag a dropped comment-continuation line even though the output still parses" in {
      val refactored =
        """object Sample {
          |  // explains the calculation
          |  def calc(x: Int): Int = {
          |    val doubled = x * 2
          |    doubled + 1
          |  }
          |}""".stripMargin
      val verdict = ReproductionIntegrity.validate(original, refactored, nothingEditable)
      verdict.ok shouldBe false
      verdict.violations shouldBe List(DroppedLine(3, "  // across two comment lines"))
    }

    "flag a dropped closing brace with its 1-indexed line number" in {
      val refactored =
        """object Sample {
          |  // explains the calculation
          |  // across two comment lines
          |  def calc(x: Int): Int = {
          |    val doubled = x * 2
          |    doubled + 1
          |}""".stripMargin
      val verdict = ReproductionIntegrity.validate(original, refactored, nothingEditable)
      verdict.ok shouldBe false
      verdict.violations shouldBe List(DroppedLine(7, "  }"))
    }

    "flag an altered non-editable line with its replacement" in {
      val refactored =
        """object Sample {
          |  // explains the calculation
          |  // across two comment lines
          |  def calc(x: Int): Int = {
          |    val doubled = x * 3
          |    doubled + 1
          |  }
          |}""".stripMargin
      val verdict = ReproductionIntegrity.validate(original, refactored, nothingEditable)
      verdict.ok shouldBe false
      verdict.violations shouldBe List(AlteredLine(5, "    val doubled = x * 2", Some("    val doubled = x * 3")))
    }

    "treat a whitespace-only change as an alteration" in {
      val refactored = original.replace("    doubled + 1", "      doubled + 1")
      val verdict = ReproductionIntegrity.validate(original, refactored, nothingEditable)
      verdict.violations shouldBe List(AlteredLine(6, "    doubled + 1", Some("      doubled + 1")))
    }

    "pass pure insertions" in {
      val refactored =
        """object Sample {
          |  // explains the calculation
          |  // across two comment lines
          |  def calc(x: Int): Int = {
          |    val doubled = x * 2
          |    scribe.info(s"doubled=$doubled")
          |    doubled + 1
          |  }
          |
          |  def extra: Int = 42
          |}""".stripMargin
      ReproductionIntegrity.validate(original, refactored, nothingEditable).ok shouldBe true
    }

    "pass a removed line the editable predicate admits" in {
      val refactored =
        """object Sample {
          |  // explains the calculation
          |  def calc(x: Int): Int = {
          |    val doubled = x * 2
          |    doubled + 1
          |  }
          |}""".stripMargin
      ReproductionIntegrity.validate(original, refactored, commentsEditable).ok shouldBe true
    }

    "flag a code line moved far from its position (LCS keeps in-order matches; a move is drop + insert)" in {
      val moved =
        """object Sample {
          |  // explains the calculation
          |  // across two comment lines
          |  def calc(x: Int): Int = {
          |    doubled + 1
          |  }
          |    val doubled = x * 2
          |}""".stripMargin
      val verdict = ReproductionIntegrity.validate(original, moved, nothingEditable)
      verdict.ok shouldBe false
      verdict.violations.map(_.lineNumber) shouldBe List(5)
      verdict.violations.head.text shouldBe "    val doubled = x * 2"
    }
  }
}
