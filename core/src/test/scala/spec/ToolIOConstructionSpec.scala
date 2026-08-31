package spec

import fabric.define.{DefType, Definition}
import fabric.rw.*
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import sigil.tool.consult.TopicClassifierTool
import sigil.tool.{SchemaErgonomics, TextToolOutput, ToolExample, ToolIO, ToolIOException, ToolInput}

/**
 * ToolIO construction gates:
 *
 *   - `derived` runs the schema-ergonomics lint — a REQUIRED union
 *     field with a payload-requiring variant fails instantiation
 *     (construction-time enforcement of the rule
 *     [[ToolInputErgonomicsAuditSpec]] audits).
 *   - `withSchema` round-trips a probe value through both the
 *     definition and the RW and throws on disagreement.
 *   - `withExamples` validates each example against the schema; a
 *     constraint-violating example throws, naming the example.
 */
class ToolIOConstructionSpec extends AnyWordSpec with Matchers {

  "the schema-ergonomics rule (enforced at boot, not construction)" should {

    // Union shapes depend on the app's registered polymorphic subtypes,
    // so `ToolIO.derived` never lints — the boot completeness pass does,
    // against the final registered state. These pin the rule through
    // that seam.

    "construct a required-union input without throwing (verdict is boot's)" in {
      noException should be thrownBy ToolIO.derived[UnfillableUnionInput, TextToolOutput]
    }

    "flag a required union field whose variant requires a nested member at boot" in {
      val findings = SchemaErgonomics.unfillableUnionFindings(
        sigil.tool.DefinitionToSchema(summon[fabric.rw.RW[UnfillableUnionInput]].definition))
      findings should not be empty
      findings.head should include("predicate")
    }

    "accept the same union when the field is optional" in {
      SchemaErgonomics.unfillableUnionFindings(
        sigil.tool.DefinitionToSchema(summon[fabric.rw.RW[OptionalUnionInput]].definition)) shouldBe empty
    }

    "accept a union nested under an OPTIONAL parent — the whole subtree is skippable" in {
      SchemaErgonomics.unfillableUnionFindings(
        sigil.tool.DefinitionToSchema(summon[fabric.rw.RW[OptionalParentUnionInput]].definition)) shouldBe empty
    }

    "accept a list of unions — the model can always emit an empty array" in {
      SchemaErgonomics.unfillableUnionFindings(
        sigil.tool.DefinitionToSchema(summon[fabric.rw.RW[UnionListInput]].definition)) shouldBe empty
    }

    "still flag a required union nested under a required parent" in {
      val findings = SchemaErgonomics.unfillableUnionFindings(
        sigil.tool.DefinitionToSchema(summon[fabric.rw.RW[RequiredParentUnionInput]].definition))
      findings should not be empty
      findings.head should include("predicate")
    }
  }

  "ToolIO.withSchema" should {

    "reject a hand-built definition the RW cannot materialise" in {
      val mismatched = Definition(DefType.Obj(Map("kind" -> Definition(DefType.Str))))
      val ex = intercept[ToolIOException] {
        ToolIO.withSchema[CountProbeInput, TextToolOutput](mismatched)
      }
      ex.getMessage should include("disagree")
    }

    "accept a hand-built definition that agrees with the RW (the topic-classifier shape)" in {
      noException should be thrownBy new TopicClassifierTool(List("Auth flows", "Billing"))
    }
  }

  "ToolIO.withExamples" should {

    "reject an example that violates the schema's constraints, naming the example" in {
      val ex = intercept[ToolIOException] {
        ToolIO.derived[PatternedProbeInput, TextToolOutput]
          .withExamples(ToolExample("shouting example", PatternedProbeInput(word = "NOT_LOWERCASE")))
      }
      ex.getMessage should include("shouting example")
      ex.getMessage should include("pattern")
    }

    "accept a valid example and expose it on the surface" in {
      val io = ToolIO.derived[PatternedProbeInput, TextToolOutput]
        .withExamples(ToolExample("quiet example", PatternedProbeInput(word = "quiet")))
      io.examples.map(_.description) shouldBe List("quiet example")
      io.surface.example("word").asString shouldBe "quiet"
    }
  }
}

/**
 * Union whose variants require payload fields beyond the discriminator.
 */
enum UnionPredFixture derives RW {
  case RegexPred(pattern: String)
  case FieldPred(field: String, regex: String)
}

case class UnfillableUnionInput(predicate: UnionPredFixture) extends ToolInput derives RW

case class OptionalUnionInput(predicate: Option[UnionPredFixture] = None) extends ToolInput derives RW

/**
 * Carrier whose own `predicate` is required — required only when the
 * CARRIER is required too.
 */
case class UnionCarrierFixture(predicate: UnionPredFixture) derives RW

case class OptionalParentUnionInput(advanced: Option[UnionCarrierFixture] = None) extends ToolInput derives RW

case class RequiredParentUnionInput(advanced: UnionCarrierFixture) extends ToolInput derives RW

case class UnionListInput(predicates: List[UnionPredFixture]) extends ToolInput derives RW

case class CountProbeInput(count: Int) extends ToolInput derives RW

case class PatternedProbeInput(@pattern("^[a-z]+$") word: String) extends ToolInput derives RW
