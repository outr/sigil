package spec

import fabric.*
import fabric.rw.*
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import rapid.Task
import sigil.provider.{CallId, ProviderEvent, ToolCallAccumulator}
import sigil.tool.{DefinitionToSchema, TextToolOutput, Tool, ToolContext, ToolInput, ToolName, ToolResult}

import spec.EnumSchemaShapeSpec.*
import sigil.tool.ToolRoster

/**
 * Acceptance for the singleton-only sealed-hierarchy schema shape — every
 * subtype is a parameterless case object, so the wire schema should
 * advertise the leaf names and the decoder should accept the leaf form
 * the same way Sigil's other wire-read sites already do.
 *
 * The Scala 3 enum case (every case-object branch is `DefType.Null`) and
 * the open `PolyType` case (every registered subtype is
 * `DefType.Obj(empty)`) both qualify. Hierarchies with at least one
 * payload-bearing subtype keep the `oneOf`-over-objects encoding.
 */
class EnumSchemaShapeSpec extends AnyWordSpec with Matchers {

  TestSigil.initFor(getClass.getSimpleName)

  private val complexitySchema: Json = DefinitionToSchema(summon[RW[ComplexityInput]].definition)
  private val workTypeSchema: Json   = DefinitionToSchema(summon[RW[WorkTypeInput]].definition)
  private val shapeSchema: Json      = DefinitionToSchema(summon[RW[ShapeInput]].definition)

  /** Minimal test tool — only the schema and inputRW matter for the dispatch checks. */
  private object ComplexityTool extends Tool {
    type Input  = ComplexityInput
    type Output = TextToolOutput
    val inputRW    = summon[RW[ComplexityInput]]
    val outputRW   = summon[RW[TextToolOutput]]
    val name       = ToolName("complexity_test_tool")
    val description = "Test tool that takes a singleton-only enum field."
    override def executeResult(input: ComplexityInput, ctx: ToolContext): Task[ToolResult[TextToolOutput]] =
      Task.pure(ToolResult.Success(TextToolOutput(input.complexity.toString)))
  }

  private object WorkTypeTool extends Tool {
    type Input  = WorkTypeInput
    type Output = TextToolOutput
    val inputRW    = summon[RW[WorkTypeInput]]
    val outputRW   = summon[RW[TextToolOutput]]
    val name       = ToolName("worktype_test_tool")
    val description = "Test tool that takes a singleton-only open-PolyType field."
    override def executeResult(input: WorkTypeInput, ctx: ToolContext): Task[ToolResult[TextToolOutput]] =
      Task.pure(ToolResult.Success(TextToolOutput(input.workType.toString)))
  }

  /** Drive args through the same accumulator the providers use; return the terminal event. */
  private def accumulate(tool: Tool, args: String): ProviderEvent = {
    val acc = new ToolCallAccumulator(ToolRoster(Vector(tool)), providerKey = "test")
    acc.start(0, CallId("call-0"), tool.schema.name.value)
    acc.appendArgs(0, args)
    acc.complete().last
  }

  "(1) singleton-only Scala 3 enum schema advertises leaf-only enum strings" should {
    "render Complexity as a string enum with no parent prefix on any value" in {
      val field = complexitySchema("properties")("complexity")
      field("type") shouldBe str("string")
      val values = field("enum").asArr.value.map(_.asString).toSet
      values shouldBe Set("Low", "Medium", "High", "VeryHigh")
      values.foreach(v => v should not include ".")
    }
  }

  "(1b) singleton-only open PolyType schema advertises leaf-only enum strings" should {
    "render WorkType as a string enum, not oneOf-over-objects" in {
      val field = workTypeSchema("properties")("workType")
      field("type") shouldBe str("string")
      field.get("oneOf") shouldBe None
      val values = field("enum").asArr.value.map(_.asString).toSet
      values shouldBe Set("CodingWork", "AnalysisWork", "CreativeWork")
    }
  }

  "(2) mixed singleton + payload hierarchies keep the oneOf-over-objects encoding" should {
    "render Shape as oneOf so subtypes with fields keep their structural shape" in {
      val field = shapeSchema("properties")("shape")
      field.get("oneOf") should not be None
      field.get("enum") shouldBe None
      val branches = field("oneOf").asArr.value
      branches.size shouldBe 3
      // Each branch is an object with a `type` discriminator. The Square branch carries `side`.
      val byDiscriminator: Map[String, Json] = branches.map { b =>
        b("properties")("type")("const").asString -> b
      }.toMap
      byDiscriminator.keySet shouldBe Set("Square", "Triangle", "Hexagon")
      byDiscriminator("Square")("properties").asObj.value.keySet should contain ("side")
    }
  }

  "(3) tool input decoder accepts bare leaf forms" should {
    "decode complexity = \"Medium\" into TestComplexity.Medium" in {
      accumulate(ComplexityTool, """{"complexity": "Medium"}""") match {
        case ProviderEvent.ToolCallComplete(_, wc) =>
          wc.inputFor(ComplexityTool).map(_.complexity) shouldBe Some(TestComplexity.Medium)
        case other => fail(s"expected ToolCallComplete(ComplexityInput); got $other")
      }
    }

    "decode workType = \"AnalysisWork\" into AnalysisWork (the bare-string form a model emits)" in {
      accumulate(WorkTypeTool, """{"workType": "AnalysisWork"}""") match {
        case ProviderEvent.ToolCallComplete(_, wc) =>
          wc.inputFor(WorkTypeTool).map(_.workType) shouldBe Some(AnalysisWork)
        case other => fail(s"expected ToolCallComplete(WorkTypeInput); got $other")
      }
    }
  }

  "(4) tool input decoder still accepts the prefixed form for backwards compatibility" should {
    "decode complexity = \"TestComplexity.Medium\" (fabric parent-prefix form)" in {
      accumulate(ComplexityTool, """{"complexity": "TestComplexity.Medium"}""") match {
        case ProviderEvent.ToolCallComplete(_, wc) =>
          wc.inputFor(ComplexityTool).map(_.complexity) shouldBe Some(TestComplexity.Medium)
        case other => fail(s"expected ToolCallComplete(ComplexityInput); got $other")
      }
    }

    "decode workType = {\"type\": \"AnalysisWork\"} (fabric Obj form for in-flight clients)" in {
      accumulate(WorkTypeTool, """{"workType": {"type": "AnalysisWork"}}""") match {
        case ProviderEvent.ToolCallComplete(_, wc) =>
          wc.inputFor(WorkTypeTool).map(_.workType) shouldBe Some(AnalysisWork)
        case other => fail(s"expected ToolCallComplete(WorkTypeInput); got $other")
      }
    }
  }

  "(5) end-to-end accumulator dispatch with bare-leaf model emit lands successfully" should {
    "produce a ToolCallComplete (not an Error) for the Haiku-style bare-string workType" in {
      // Stub for the Sage L196 wire signature: model emits the bare leaf as the field's value.
      // No `_provider_error` should surface; the orchestrator should see a typed input.
      val terminal = accumulate(WorkTypeTool, """{"workType": "AnalysisWork", "label": "scoping"}""")
      terminal shouldBe a [ProviderEvent.ToolCallComplete]
      val ProviderEvent.ToolCallComplete(_, wc) = terminal: @unchecked
      val input = wc.inputFor(WorkTypeTool).getOrElse(fail(s"expected a decoded WorkTypeTool call; got $wc"))
      input.workType shouldBe AnalysisWork
      input.label    shouldBe "scoping"
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed).sync()
  }
}

object EnumSchemaShapeSpec {

  /** Scala 3 enum with parameterless cases — mirrors `sigil.provider.Complexity`. */
  enum TestComplexity derives RW {
    case Low, Medium, High, VeryHigh
  }

  /** Open `PolyType` whose subtypes are all `case object` — mirrors `sigil.provider.WorkType`. */
  sealed trait TestWorkType
  case object CodingWork extends TestWorkType
  case object AnalysisWork extends TestWorkType
  case object CreativeWork extends TestWorkType
  object TestWorkType extends PolyType[TestWorkType]()(using scala.reflect.ClassTag(classOf[TestWorkType])) {
    register(
      RW.static(CodingWork),
      RW.static(AnalysisWork),
      RW.static(CreativeWork)
    )
  }

  /** Mixed hierarchy — one subtype carries fields. Must keep `oneOf`-over-objects. */
  sealed trait TestShape derives RW
  case class Square(side: Int) extends TestShape
  case object Triangle extends TestShape
  case object Hexagon extends TestShape

  case class ComplexityInput(complexity: TestComplexity, note: String = "") extends ToolInput derives RW
  case class WorkTypeInput(workType: TestWorkType, label: String = "") extends ToolInput derives RW
  case class ShapeInput(shape: TestShape) extends ToolInput derives RW
}
