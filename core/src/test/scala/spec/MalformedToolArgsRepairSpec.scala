package spec

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import sigil.provider.{CallId, ProviderEvent, ToolCallAccumulator}
import sigil.tool.core.RespondOptionsTool
import sigil.tool.model.RespondOptionsInput

/**
 * Sigil #408 — a weak model can emit mechanically-recoverable malformed tool
 * args (a doubled opening brace on an array element). Rather than hard-failing
 * with `Failed to parse args` — losing a user-facing `respond_options` offer
 * when the model doesn't self-correct on retry — the accumulator runs a
 * bounded, strictly-mechanical JSON-repair pass first, mirroring the existing
 * #171 (array-when-object) / #398 (fenced-JSON) coercions. Repair is
 * conservative: it must not touch string CONTENT (e.g. `{{var}}` templates),
 * and a genuinely-unrecoverable arg string still surfaces the hard diagnostic.
 */
class MalformedToolArgsRepairSpec extends AnyWordSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  private def run(args: String): Vector[ProviderEvent] = {
    val acc = new ToolCallAccumulator(Vector(RespondOptionsTool), providerKey = "test")
    acc.start(0, CallId("c1"), "respond_options")
    acc.appendArgs(0, args)
    acc.complete()
  }

  "ToolCallAccumulator JSON repair (#408)" should {

    "repair a doubled opening brace and decode to a valid RespondOptionsInput" in {
      val args = """{"prompt":"pick one","allowMultiple":false,"options":[{"label":"a","value":"a"},{{"label":"b","value":"b"}]}"""
      val events = run(args)
      withClue(s"events=${events.map(_.getClass.getSimpleName)}\n") {
        events.collect { case e: ProviderEvent.Error => e } shouldBe empty
        events.collectFirst { case c: ProviderEvent.ToolCallComplete => c.input } match {
          case Some(in: RespondOptionsInput) => in.options.map(_.value) shouldBe List("a", "b")
          case other => fail(s"expected RespondOptionsInput, got $other")
        }
      }
    }

    "strip a trailing comma before a close" in {
      val args = """{"prompt":"pick","allowMultiple":false,"options":[{"label":"a","value":"a"},]}"""
      run(args).collectFirst { case c: ProviderEvent.ToolCallComplete => c.input } match {
        case Some(in: RespondOptionsInput) => in.options.map(_.value) shouldBe List("a")
        case other => fail(s"expected RespondOptionsInput, got $other")
      }
    }

    "not corrupt a doubled brace that lives inside a string value" in {
      // `{{item}}`-style template text must survive verbatim — repair is
      // structural only, never inside a string.
      val args = """{"prompt":"use {{item}} here","allowMultiple":false,"options":[{"label":"a","value":"a"}]}"""
      run(args).collectFirst { case c: ProviderEvent.ToolCallComplete => c.input } match {
        case Some(in: RespondOptionsInput) => in.prompt shouldBe "use {{item}} here"
        case other => fail(s"expected RespondOptionsInput, got $other")
      }
    }

    "still surface a hard error for genuinely-unrecoverable args (repair is bounded)" in {
      // Missing comma + missing close — no mechanical rule recovers this.
      val args = """{"prompt":"pick","allowMultiple":false,"options":[{"label":"a" "value":"a"}"""
      val events = run(args)
      events.collect { case e: ProviderEvent.Error => e } should not be empty
      events.collectFirst { case c: ProviderEvent.ToolCallComplete => c } shouldBe None
    }
  }
}
