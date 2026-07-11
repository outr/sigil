package spec

import fabric.*
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import sigil.tool.WireSurface

/**
 * `create_workflow` / `update_workflow` carry their step list as a flat,
 * `kind`-tagged `WorkflowStepSpec` (an atomic record — no model-facing union),
 * and `triggers: List[WorkflowTrigger]` as an open `PolyType` whose subtypes
 * register in `WorkflowSigil.mixinPolymorphicRegistrations`.
 *
 * Two guards here:
 *   - `steps.items` must render as a flat object with a `kind` discriminator
 *     property — NOT a `oneOf` of nested variants (the unfillable shape that
 *     drove a model to placeholder-fill a single sub-workflow step; #338/#372).
 *   - `triggers.items` must render as a discriminated `oneOf`, not
 *     `array<string>`. The enclosing tool-input `Definition` is a lazy-val
 *     snapshot frozen on first `.definition` access; if the mixin trigger
 *     registration runs AFTER that freeze, the poly is empty and the field
 *     renders as `array<string>` — which no model can fill. The framework boot
 *     must register the mixin polytypes first.
 */
class WorkflowToolSchemaSpec extends AnyWordSpec with Matchers {

  // Phase-1 registration, exercised through the real WorkflowSigil boot
  // ordering (no DB needed). This forces the tool-input Definition freeze at
  // the same point production does.
  TestWorkflowSigil.polymorphicRegistrations.sync()

  private def get(j: Json, key: String): Json = j match {
    case Obj(m) => m.getOrElse(key, fail(s"missing key '$key' in $j"))
    case other => fail(s"expected object for key '$key', got: $other")
  }

  private def arrayItems(toolName: String, field: String): Json = {
    val tool = TestWorkflowSigil.staticTools
      .find(_.name.value == toolName)
      .getOrElse(fail(s"$toolName not registered in staticTools"))
    val schema = WireSurface.fromTool(tool).schema
    get(get(get(schema, "properties"), field), "items")
  }

  "create_workflow schema" should {
    "render a step's `arguments` as a structured object, NOT a stringified-JSON string (#373)" in {
      val items = arrayItems("create_workflow", "steps")
      val argsSchema = items match {
        case Obj(m) =>
          get(Obj(m), "properties") match {
            case Obj(p) => p.getOrElse("arguments", fail(s"steps.items has no `arguments` property: ${p.keySet}"))
            case other => fail(s"steps.items.properties is not an object: $other")
          }
        case other => fail(s"steps.items is not an object: $other")
      }
      // A stringified-JSON `arguments` is the #373 footgun — the model can't nest
      // a tool's params into a string, so it spills them into sibling fields
      // (workflowId / variables). The field must accept a JSON OBJECT so every
      // tool parameter has a home.
      val ty = argsSchema match {
        case Obj(m) => m.get("type").map(_.toString.replace("\"", ""))
        case _ => None
      }
      withClue(s"arguments schema = $argsSchema (type=$ty)\n") {
        ty should not be Some("string")
      }
    }

    "render `triggers` as a discriminated oneOf, not array<string>" in {
      arrayItems("create_workflow", "triggers") match {
        case Obj(m) => withClue(s"triggers.items keys = ${m.keySet}: ")(m.keySet should contain("oneOf"))
        case other => fail(s"triggers.items is not an object: $other")
      }
    }
  }
}
