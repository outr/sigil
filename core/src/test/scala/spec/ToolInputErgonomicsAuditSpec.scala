package spec

import fabric.{Arr, Json, Obj, Str, arr, obj, str}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import sigil.tool.{DefinitionToSchema, Tool}

/**
 * Systemic guard (the higher-level lever behind sigil #338): a tool input
 * a model reliably can't fill is a framework defect, not a model failure.
 * The specific footgun #338 exposed — a REQUIRED field that's a `oneOf`
 * union whose variant requires a *nested* field (the discriminator plus a
 * real payload field) — was unfillable on a frontier model 4/4 attempts.
 * No didactic error rescues it; the schema shape itself is wrong.
 *
 * This audit walks each framework tool's generated input schema and fails
 * if any tool ships that shape. Scoped to framework-shipped tools (which
 * we control); the detection ([[unfillableUnionFindings]]) is reusable for
 * apps that want to lint their own tools the same way.
 */
class ToolInputErgonomicsAuditSpec extends AnyWordSpec with Matchers {

  /**
   * Delegates to the shared framework rule — the same predicate
   * [[sigil.tool.ToolIO.derived]] enforces at construction. Reusable
   * for apps that want to lint their own tools.
   */
  def unfillableUnionFindings(schema: Json): List[String] =
    sigil.tool.SchemaErgonomics.unfillableUnionFindings(schema)

  def auditFindings(tools: Iterable[Tool]): List[String] =
    tools.toList.flatMap { t =>
      unfillableUnionFindings(DefinitionToSchema(t.inputRW.definition)).map(f => s"${t.name.value}: $f")
    }

  /**
   * A representative, structured-input slice of the framework roster —
   * the tools most likely to carry unions, including the whole
   * bulk-output ladder #338/#339 belong to.
   */
  private def frameworkTools: List[Tool] = {
    val fs = new sigil.tool.fs.LocalFileSystemContext
    sigil.tool.core.CoreTools.all.toList ++ List[Tool](
      new sigil.tool.fs.GrepTool(fs),
      new sigil.tool.fs.GlobTool(fs),
      new sigil.tool.fs.BashTool(fs),
      sigil.tool.context.ReloadContentTool
    )
  }

  "Framework tool inputs" should {
    "never require a oneOf/anyOf union with a payload-requiring variant (#338 footgun)" in {
      val findings = auditFindings(frameworkTools)
      withClue(s"unfillable-union findings:\n  ${findings.mkString("\n  ")}\n") {
        findings shouldBe empty
      }
    }

    "flag the #338 shape when it IS present (audit self-check)" in {
      // A synthetic input mirroring the old filter_container shape: a
      // required `predicate` field that's a sealed-trait union whose
      // variant requires `pattern`.
      val badSchema = obj(
        "type" -> str("object"),
        "required" -> arr(str("predicate")),
        "properties" -> obj(
          "predicate" -> obj(
            "oneOf" -> arr(
              obj("type" -> str("object"), "required" -> arr(str("type"), str("pattern")))
            )
          )
        )
      )
      unfillableUnionFindings(badSchema) should not be empty
    }
  }
}
