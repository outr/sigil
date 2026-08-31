package spec

import fabric.Str
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import sigil.provider.Reliability
import sigil.tool.core.RespondTool
import sigil.tool.{DecodeError, DecodeViolation, RefusalPayload, ViolationKind}

/**
 * A refusal's verbosity follows the running model's
 * [[sigil.provider.ModelProfile.toolCallReliability]]. A model that
 * emits well-formed calls already has the schema in its roster — the
 * violated rule is the whole message. A wobbly emitter gets the schema
 * and a worked example pinned next to the rejection, which is what
 * makes its next attempt land.
 */
class RefusalVerbositySpec extends AnyWordSpec with Matchers {

  private val tool = RespondTool
  private val rule = "content must not be empty"

  private def error: DecodeError = DecodeError(
    violations = List(DecodeViolation(List("content"), "must not be empty", ViolationKind.Constraint)),
    raw = Str("{}"))

  "RefusalPayload verbosity" should {

    "pin the schema and a worked example for a wobbly emitter" in {
      val body = RefusalPayload.malformedArgs(
        Some(tool),
        tool.name.value,
        error,
        Str("{}"),
        reliability = Reliability.Wobbly)
      body should include(s"Schema for `${tool.name.value}`")
      body should include(s"Example call for `${tool.name.value}`")
    }

    "do the same for an unreliable emitter" in {
      val body = RefusalPayload.malformedArgs(
        Some(tool),
        tool.name.value,
        error,
        Str("{}"),
        reliability = Reliability.Unreliable)
      body should include("Schema for")
    }

    "state the rule alone for a solid emitter" in {
      val body = RefusalPayload.malformedArgs(
        Some(tool),
        tool.name.value,
        error,
        Str("{}"),
        reliability = Reliability.Solid)
      body should include("violated schema constraints")
      body should not include "Schema for"
      body should not include "Example call for"
    }

    "spend materially fewer characters on a solid emitter" in {
      val wobbly = RefusalPayload.enrichRule(tool, rule, reliability = Reliability.Wobbly)
      val solid = RefusalPayload.enrichRule(tool, rule, reliability = Reliability.Solid)
      solid.length should be < wobbly.length
      solid should include(rule)
    }

    "default to the verbose form when no reliability is supplied" in {
      RefusalPayload.enrichRule(tool, rule) shouldBe
        RefusalPayload.enrichRule(tool, rule, reliability = Reliability.Wobbly)
    }
  }
}
