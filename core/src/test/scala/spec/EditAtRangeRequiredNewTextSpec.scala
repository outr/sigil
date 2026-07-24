package spec

import fabric.*
import fabric.rw.*
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import sigil.tool.model.EditAtRangeInput

/**
 * `edit_at_range`'s `newText` is REQUIRED — an omitted replacement
 * must be a decode error, never a silent delete. A destructive
 * default on a dropped parameter hands data loss to exactly the
 * small models most likely to drop one. Deleting a range is always
 * an explicit act: `newText = ""`.
 */
class EditAtRangeRequiredNewTextSpec extends AnyWordSpec with Matchers {

  private def base: Json = obj(
    "path" -> str("src/A.scala"),
    "startLine" -> num(1),
    "startChar" -> num(0),
    "endLine" -> num(2),
    "endChar" -> num(0)
  )

  "EditAtRangeInput decode" should {

    "reject args missing newText — never silently delete" in {
      val result = scala.util.Try(base.as[EditAtRangeInput])
      result.isFailure shouldBe true
      result.failed.get.getMessage should include("newText")
    }

    "accept an explicit empty newText as the delete form" in {
      val json = base.merge(obj("newText" -> str("")))
      val decoded = json.as[EditAtRangeInput]
      decoded.newText shouldBe ""
    }
  }
}
