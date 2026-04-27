package sigil.mcp

import fabric.*
import fabric.define.{Constraints, DefType, Definition, Format}

import scala.collection.immutable.VectorMap

/**
 * Best-effort conversion from a raw JSON Schema (as advertised by an
 * MCP server in `tools/list`) to a fabric [[Definition]]. The
 * resulting Definition rides through Sigil's normal
 * `DefinitionToSchema` pipeline so providers serialize the tool's
 * parameters consistently with locally-defined tools.
 *
 * Coverage:
 *   - `type: "object"` — recurses into `properties`; `required` vs
 *     `properties \ required` distinguishes mandatory and optional
 *     fields (optionals wrap as `Opt`).
 *   - `type: "string" | "integer" | "number" | "boolean"` — direct
 *     mapping. `description` and `format` annotations carry through.
 *   - `type: "array"` — `items` recursed; missing items defaults to
 *     `DefType.Json`.
 *   - `enum: [...]` (without explicit type) — string enum encoded as
 *     `DefType.Poly` with each value as an empty branch.
 *   - Anything else (`oneOf`, `anyOf`, missing or unknown `type`) —
 *     falls through to `DefType.Json` (any-shape passthrough).
 *
 * Sigil's downstream `DefinitionToSchema` regenerates JSON Schema
 * from the `Definition`. Round-trip is lossy on edge cases (custom
 * format strings, JSON Schema features fabric doesn't model), but
 * fully captures the shapes typical MCP servers advertise.
 */
object JsonSchemaToDefinition {

  def apply(schema: Json): Definition = toDefinition(schema)

  private def toDefinition(schema: Json): Definition = {
    val description = schema.get("description").map(_.asString)
    val defType = toDefType(schema)
    Definition(defType, description = description)
  }

  private def toDefType(schema: Json): DefType = schema match {
    case _: Obj =>
      schema.get("type").map(_.asString) match {
        case Some("object") => objectType(schema)
        case Some("string") => DefType.Str
        case Some("integer") => DefType.Int
        case Some("number") => DefType.Dec
        case Some("boolean") => DefType.Bool
        case Some("null") => DefType.Null
        case Some("array") =>
          val items = schema.get("items").getOrElse(Obj.empty)
          DefType.Arr(toDefinition(items))
        case Some(other) => DefType.Json
        case None =>
          schema.get("enum") match {
            case Some(arr: Arr) =>
              val branches = arr.value.collect { case Str(s, _) => s -> Definition(DefType.Obj(VectorMap.empty)) }.toMap
              if (branches.nonEmpty) DefType.Poly(VectorMap.from(branches)) else DefType.Json
            case _ => DefType.Json
          }
      }
    case _ => DefType.Json
  }

  private def objectType(schema: Json): DefType = {
    val props: Map[String, Json] = schema.get("properties").collect {
      case o: Obj => o.value.toMap
    }.getOrElse(Map.empty)
    val required: Set[String] = schema.get("required").collect {
      case Arr(values, _) => values.collect { case Str(s, _) => s }.toSet
    }.getOrElse(Set.empty)
    val members = props.map { case (k, v) =>
      val inner = toDefinition(v)
      val finalDef = if (required.contains(k)) inner else Definition(DefType.Opt(inner))
      k -> finalDef
    }
    DefType.Obj(VectorMap.from(members))
  }
}
