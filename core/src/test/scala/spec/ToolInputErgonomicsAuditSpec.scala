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
   * The discriminator key fabric emits on every `oneOf` branch of a
   * sealed trait / data enum. A branch requiring only `type` is fine
   * (the model just emits the variant tag); requiring anything *beyond*
   * it is the unfillable case.
   */
  private val Discriminator = "type"

  private def requiredFields(obj: Map[String, Json]): List[String] =
    obj.get("required").collect { case Arr(items, _) => items.collect { case Str(s, _) => s }.toList }.getOrElse(Nil)

  private def unionBranches(obj: Map[String, Json]): List[Json] =
    obj.get("oneOf").orElse(obj.get("anyOf")).collect { case Arr(b, _) => b.toList }.getOrElse(Nil)

  private def branchRequiresPayload(branch: Json): Boolean = branch match {
    case Obj(bm) => requiredFields(bm).exists(_ != Discriminator)
    case _ => false
  }

  /**
   * Walk a schema; report the path of every REQUIRED field whose schema
   * is a union with a payload-requiring branch. Optional unions (the
   * model can skip them) are fine and not reported.
   */
  def unfillableUnionFindings(schema: Json): List[String] = {
    def walk(json: Json, path: String, isRequired: Boolean): List[String] = json match {
      case Obj(m) =>
        val here =
          if (isRequired && unionBranches(m).exists(branchRequiresPayload))
            List(s"$path — required field is a oneOf/anyOf union whose variant requires a nested field; flatten to scalar args (#338)")
          else Nil
        val props = m.get("properties").collect { case Obj(p) => p }.getOrElse(Map.empty)
        val reqHere = requiredFields(m).toSet
        val kids = props.toList.flatMap { case (k, v) => walk(v, if (path.isEmpty) k else s"$path.$k", reqHere.contains(k)) }
        // descend array element schemas (a required array of unions is the
        // same footgun one level down)
        val arrItem = m.get("items").toList.flatMap(v => walk(v, s"$path[]", isRequired))
        here ++ kids ++ arrItem
      case _ => Nil
    }
    walk(schema, "", isRequired = true)
  }

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
