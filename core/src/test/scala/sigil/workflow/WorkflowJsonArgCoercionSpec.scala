package sigil.workflow

import fabric.*
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

/**
 * Sigil #396/#398 — `SigilJobStep.coerceStringArgs` lets the "leaf computes the
 * call, tool applies it" recipe work: a tool-argument that resolves to a JSON-
 * object STRING (a prompt leaf emits text) is parsed into the [[Obj]] the tool
 * wants. #398 extends it to tolerate the near-universal markdown code fence
 * models wrap JSON in even when told not to. A genuinely non-JSON value — even
 * after fence stripping — is left untouched so the tool's decode still fails
 * with a clear error.
 */
class WorkflowJsonArgCoercionSpec extends AnyWordSpec with Matchers {

  private val expected: Json = obj(
    "path"      -> str("a"),
    "oldString" -> str("x"),
    "newString" -> str("")
  )
  private val rawJson = """{"path":"a","oldString":"x","newString":""}"""

  "SigilJobStep.coerceStringArgs" should {

    "coerce a raw JSON-object string into the object (#396)" in {
      SigilJobStep.coerceStringArgs(str(rawJson)) shouldBe expected
    }

    "coerce a ```json-fenced object string (#398)" in {
      SigilJobStep.coerceStringArgs(str(s"```json\n$rawJson\n```")) shouldBe expected
    }

    "coerce a bare ```-fenced object string with no language tag (#398)" in {
      SigilJobStep.coerceStringArgs(str(s"```\n$rawJson\n```")) shouldBe expected
    }

    "coerce a fenced object string with surrounding whitespace (#398)" in {
      SigilJobStep.coerceStringArgs(str(s"  \n```json\n$rawJson\n```\n  ")) shouldBe expected
    }

    "leave a non-JSON string untouched so the tool decode still errors clearly (#398)" in {
      SigilJobStep.coerceStringArgs(str("just some prose")) shouldBe str("just some prose")
    }

    "leave a fenced NON-JSON string untouched (no silent acceptance) (#398)" in {
      val fencedProse = "```\njust some prose\n```"
      SigilJobStep.coerceStringArgs(str(fencedProse)) shouldBe str(fencedProse)
    }

    "pass a non-Str argument through untouched" in {
      SigilJobStep.coerceStringArgs(expected) shouldBe expected
    }

    "leave a Str that parses as a JSON array untouched (tools want objects)" in {
      val arrStr = """["a","b"]"""
      SigilJobStep.coerceStringArgs(str(arrStr)) shouldBe str(arrStr)
    }
  }
}
