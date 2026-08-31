package spec

import fabric.*
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import sigil.provider.{CallId, ProviderEvent, ToolCallAccumulator}
import sigil.tool.core.RespondTool
import sigil.tool.model.{ResponseDisposition, RespondInput}
import sigil.tool.ToolRoster

/**
 * Production-path guard for the `respond` tool's `disposition` field — the
 * exact site that failed 57× in Sage's wire log. The model emitted the
 * full class-chain form `"ResponseDisposition.Success"`; an older fabric
 * derived the enum's wire-read via a bare `valueOf` with no leaf / full-path
 * tolerance, so decode threw `enum … has no case with name` and `respond`
 * never produced a result (34 paired orphan-settles poisoning history).
 *
 * fabric 1.29.3 (`genEnumMacro`) renders the enum's `DefType.Poly` keys in
 * full-chain form and accepts both wire forms on read; Sigil's
 * `WireSurface.normalizeSingletonPoly` canonicalises whatever the model
 * emits to the key fabric expects. Together they make BOTH `"Success"` and
 * `"ResponseDisposition.Success"` decode. This spec pins that on the real
 * `respond` tool — not a mirror enum — through the same `ToolCallAccumulator`
 * the providers drive, so the Sage failure cannot regress.
 */
class RespondDispositionWireDecodeSpec extends AnyWordSpec with Matchers {

  TestSigil.initFor(getClass.getSimpleName)

  /**
   * Drive args through the provider accumulator; return the terminal event.
   */
  private def accumulate(args: String): ProviderEvent = {
    val acc = new ToolCallAccumulator(ToolRoster(Vector(RespondTool)), providerKey = "test")
    acc.start(0, CallId("call-0"), RespondTool.schema.name.value)
    acc.appendArgs(0, args)
    acc.complete().last
  }

  private def decoded(args: String): RespondInput =
    accumulate(args) match {
      case ProviderEvent.ToolCallComplete(_, wc) =>
        wc.inputFor(RespondTool).getOrElse(fail(s"expected a decoded respond call; got $wc"))
      case other => fail(s"expected ToolCallComplete(RespondInput); got $other")
    }

  "the respond tool schema" should {
    "advertise disposition as a leaf-only string enum" in {
      val field = RespondTool.wireSurface.schema("properties")("disposition")
      field("type") shouldBe str("string")
      val values = field("enum").asArr.value.map(_.asString).toSet
      values shouldBe Set("Success", "Failure")
      values.foreach(v => v should not include ".")
    }
  }

  "the respond tool decoder" should {
    "accept the bare leaf disposition the schema advertises" in {
      decoded(
        """{"topicLabel":"t","topicSummary":"s","content":"hi","disposition":"Failure","endsTurn":true}"""
      ).disposition shouldBe ResponseDisposition.Failure
    }

    "accept the full class-chain disposition the model emitted in Sage (was a 57× decode failure)" in {
      decoded(
        """{"topicLabel":"t","topicSummary":"s","content":"hi","disposition":"ResponseDisposition.Success","endsTurn":true}"""
      ).disposition shouldBe ResponseDisposition.Success
    }

    "fall back to the Success default when disposition is omitted" in {
      decoded(
        """{"topicLabel":"t","topicSummary":"s","content":"hi","endsTurn":true}"""
      ).disposition shouldBe ResponseDisposition.Success
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed).sync()
  }
}
