package spec

import fabric.*
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import sigil.tool.WireSurface

/**
 * `create_workflow` / `update_workflow` carry polymorphic array fields —
 * `steps: List[WorkflowStepInput]` and `triggers: List[WorkflowTrigger]`,
 * both open `PolyType`s whose subtypes register in
 * `WorkflowSigil.mixinPolymorphicRegistrations`. The enclosing tool-input
 * `Definition` is a lazy-val snapshot frozen on first `.definition` access;
 * `ToolInput.register` forces that access during boot. If the mixin
 * registration runs AFTER `ToolInput.register`, the snapshot freezes with an
 * empty poly and the schema renders the fields as `array<string>` — which no
 * model can fill (deserialize fails "Lookup not found: type"). The framework
 * boot must register the mixin polytypes first.
 */
class WorkflowToolSchemaSpec extends AnyWordSpec with Matchers {

  // Phase-1 registration, exercised through the real WorkflowSigil boot
  // ordering (no DB needed). This forces the tool-input Definition freeze at
  // the same point production does.
  TestWorkflowSigil.polymorphicRegistrations.sync()

  private def get(j: Json, key: String): Json = j match {
    case Obj(m) => m.getOrElse(key, fail(s"missing key '$key' in $j"))
    case other  => fail(s"expected object for key '$key', got: $other")
  }

  private def arrayItems(toolName: String, field: String): Json = {
    val tool = TestWorkflowSigil.staticTools
      .find(_.name.value == toolName)
      .getOrElse(fail(s"$toolName not registered in staticTools"))
    val schema = WireSurface.fromTool(tool).schema
    get(get(get(schema, "properties"), field), "items")
  }

  "create_workflow schema" should {
    "render `steps` as a discriminated oneOf, not array<string>" in {
      arrayItems("create_workflow", "steps") match {
        case Obj(m) => withClue(s"steps.items keys = ${m.keySet}: ") { m.keySet should contain("oneOf") }
        case other  => fail(s"steps.items is not an object: $other")
      }
    }

    "render `triggers` as a discriminated oneOf, not array<string>" in {
      arrayItems("create_workflow", "triggers") match {
        case Obj(m) => withClue(s"triggers.items keys = ${m.keySet}: ") { m.keySet should contain("oneOf") }
        case other  => fail(s"triggers.items is not an object: $other")
      }
    }
  }
}
