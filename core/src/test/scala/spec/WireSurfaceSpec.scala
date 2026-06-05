package spec

import fabric.*
import fabric.rw.*
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import rapid.Task
import sigil.tool.{
  DefinitionToSchema, InputNormalizer, TextToolOutput, Tool, ToolContext,
  ToolInput, ToolName, ToolResult, WireSurface
}

import spec.WireSurfaceSpec.*

/**
 * Covers the consolidated `WireSurface[T]` derivation that replaces three
 * independent walks of a tool input's `Definition` (`DefinitionToSchema`,
 * `InputNormalizer`, `RefusalPayload.synthesizeExample`).
 *
 * Properties the surface guarantees by construction:
 *   1. `schema` matches the byte output of `DefinitionToSchema.apply` so
 *      out-of-repo provider integrations keep emitting the same wire shape.
 *   2. `normalize` accepts every coercion `InputNormalizer.normalize`
 *      accepted (empty-string Opt(Str), literal `"null"`, string-encoded
 *      scalars, singleton-poly leaf forms).
 *   3. `example` round-trips: `decode(example)` produces a typed value.
 *   4. `decode` surfaces every violation at once, not one-at-a-time —
 *      closes the historical pain where an agent burned several turns
 *      fixing one field per iteration.
 */
class WireSurfaceSpec extends AnyWordSpec with Matchers {

  "WireSurface schema parity" should {

    "match DefinitionToSchema byte-for-byte for an enum-bearing input" in {
      val rw = summon[RW[ComplexityInput]]
      val surface = WireSurface.fromDefinition(rw.definition, rw)
      surface.schema shouldBe DefinitionToSchema(rw.definition)
    }

    "match DefinitionToSchema for a Json-bearing input" in {
      val rw = summon[RW[JsonFieldInput]]
      val surface = WireSurface.fromDefinition(rw.definition, rw)
      surface.schema shouldBe DefinitionToSchema(rw.definition)
    }

    "match DefinitionToSchema for a multi-field input" in {
      val rw = summon[RW[MultiField]]
      val surface = WireSurface.fromDefinition(rw.definition, rw)
      surface.schema shouldBe DefinitionToSchema(rw.definition)
    }
  }

  "WireSurface required-field derivation (sigil #359)" should {

    def requiredOf(rw: RW[?]): Set[String] = {
      val surface = WireSurface.fromDefinition(rw.definition, rw)
      surface.schema("required").asVector.map(_.asString).toSet
    }

    "exclude defaulted and optional fields, keep mandatory ones" in {
      // MultiField: label (mandatory) | complexity = Medium (default) |
      // retries = 0 (default) | optional: Option = None (opt + default).
      requiredOf(summon[RW[MultiField]]) shouldBe Set("label")
    }

    "not force `respond` to emit defaulted disposition/keywords" in {
      // The #359 trigger: disposition (= Success) and keywords (= Nil) were
      // marked required, forcing weak models to fabricate a disposition on
      // endsTurn:false continuations. content/topicLabel/topicSummary/
      // endsTurn have no default and stay required.
      val required = requiredOf(summon[RW[sigil.tool.model.RespondInput]])
      required should contain allOf ("content", "topicLabel", "topicSummary", "endsTurn")
      required should not contain "disposition"
      required should not contain "keywords"
    }
  }

  "WireSurface normalize parity" should {

    "coerce empty-string Opt(Str) to Null" in {
      val rw = summon[RW[OptionalStrInput]]
      val surface = WireSurface.fromDefinition(rw.definition, rw)
      val raw = obj("note" -> str(""))
      surface.normalize(raw) shouldBe InputNormalizer.normalize(raw, rw.definition)
    }

    "coerce string-encoded scalars" in {
      val rw = summon[RW[ScalarInput]]
      val surface = WireSurface.fromDefinition(rw.definition, rw)
      val raw = obj("count" -> str("42"), "rate" -> str("1.5"), "active" -> str("true"))
      surface.normalize(raw) shouldBe InputNormalizer.normalize(raw, rw.definition)
    }

    "canonicalise singleton-poly leaf form" in {
      val rw = summon[RW[ComplexityInput]]
      val surface = WireSurface.fromDefinition(rw.definition, rw)
      val raw = obj("complexity" -> str("Medium"))
      surface.normalize(raw) shouldBe InputNormalizer.normalize(raw, rw.definition)
    }
  }

  "WireSurface example round-trip" should {

    "produce an example that decodes back to a typed value" in {
      val rw = summon[RW[MultiField]]
      val surface = WireSurface.fromDefinition(rw.definition, rw)
      val example = surface.example
      surface.decode(example) match {
        case Right(_) => succeed
        case Left(err) => fail(s"example failed to decode: ${err.render}")
      }
    }

    "produce an enum-bearing example whose enum value is on the schema's enum list" in {
      val rw = summon[RW[ComplexityInput]]
      val surface = WireSurface.fromDefinition(rw.definition, rw)
      val example = surface.example
      surface.decode(example) match {
        case Right(_) => succeed
        case Left(err) => fail(s"enum example failed to decode: ${err.render}")
      }
    }
  }

  "WireSurface multi-violation surfacing" should {

    "report every constraint violation in a single decode failure" in {
      val rw = summon[RW[ConstrainedInput]]
      val surface = WireSurface.fromDefinition(rw.definition, rw)
      // Both `slug` (pattern violation: contains uppercase) and `count`
      // (range violation: above maximum) fail simultaneously. The
      // historical one-at-a-time path produced one error; the
      // consolidated surface produces both.
      val raw = obj("slug" -> str("BAD-Slug"), "count" -> num(9999))
      surface.decode(raw) match {
        case Right(_) =>
          fail("expected decode failure; got Right")
        case Left(err) =>
          err.violations.size should be >= 2
          val rendered = err.render
          rendered should include("slug")
          rendered should include("count")
      }
    }

    "report a field-path scoped violation when nested" in {
      val rw = summon[RW[NestedConstrained]]
      val surface = WireSurface.fromDefinition(rw.definition, rw)
      // The nested `inner.slug` has a pattern violation; surface should
      // path-qualify the violation.
      val raw = obj("inner" -> obj("slug" -> str("BAD")))
      surface.decode(raw) match {
        case Right(_) => fail("expected decode failure")
        case Left(err) =>
          err.violations.exists(v => v.path.mkString(".").contains("slug")) shouldBe true
      }
    }
  }

  "Tool.wireSurface" should {

    "round-trip a typed input through decode" in {
      val example = obj("label" -> str("hello"), "count" -> num(1))
      ExampleTool.wireSurface.decode(example) match {
        case Right(typed) =>
          typed.label shouldBe "hello"
          typed.count shouldBe 1
        case Left(err) => fail(s"decode failed: ${err.render}")
      }
    }
  }
}

object WireSurfaceSpec {

  enum Complexity derives RW {
    case Low, Medium, High, VeryHigh
  }

  case class ComplexityInput(complexity: Complexity, note: String = "") extends ToolInput derives RW
  case class JsonFieldInput(name: String, payload: Json = obj()) extends ToolInput derives RW
  case class OptionalStrInput(note: Option[String] = None) extends ToolInput derives RW
  case class ScalarInput(count: Int, rate: Double, active: Boolean) extends ToolInput derives RW

  case class MultiField(label: String,
                        complexity: Complexity = Complexity.Medium,
                        retries: Int = 0,
                        optional: Option[String] = None) extends ToolInput derives RW

  /** Two-violation fixture — both `slug` (regex) and `count` (max) fail. */
  case class ConstrainedInput(
    @fabric.rw.pattern("^[a-z0-9-]+$") slug: String,
    @fabric.rw.maximum(100.0) count: Int
  ) extends ToolInput derives RW

  case class Inner(@fabric.rw.pattern("^[a-z]+$") slug: String) derives RW
  case class NestedConstrained(inner: Inner) extends ToolInput derives RW

  case class ExampleInput(label: String, count: Int) extends ToolInput derives RW

  case object ExampleTool extends Tool {
    type Input  = ExampleInput
    type Output = TextToolOutput
    val inputRW  = summon[RW[ExampleInput]]
    val outputRW = summon[RW[TextToolOutput]]
    val name        = ToolName("wire_surface_example_tool")
    val description = "Test tool for WireSurfaceSpec — decode round-trip via Tool.wireSurface."
    override def executeResult(input: ExampleInput, ctx: ToolContext): Task[ToolResult[TextToolOutput]] =
      Task.pure(ToolResult.Success(TextToolOutput(input.label)))
  }
}
