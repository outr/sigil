package sigil.tool

import fabric.*
import fabric.define.{Constraints, DefType, Definition, Format}

import scala.collection.immutable.VectorMap

/**
 * Convert a raw JSON Schema (as advertised by an MCP server in
 * `tools/list`) to a fabric [[Definition]]. The resulting Definition
 * rides through Sigil's normal `DefinitionToSchema` pipeline so
 * providers serialize the tool's parameters consistently with
 * locally-defined tools.
 *
 * Coverage:
 *   - **Objects** — `type: "object"` recurses into `properties`;
 *     `required` distinguishes mandatory and optional fields.
 *   - **Primitives** — `string` / `integer` / `number` / `boolean` /
 *     `null`. `description` and `format` carry through; numeric and
 *     string `Constraints` (pattern, min/maxLength, minimum, maximum,
 *     exclusiveMinimum / Maximum, multipleOf) carry through.
 *   - **Arrays** — `items` recurses; missing items defaults to
 *     `DefType.Json`. `minItems` / `maxItems` / `uniqueItems` carry
 *     through.
 *   - **Enums** — `enum: [...]` (string-only, without explicit type)
 *     becomes a `DefType.Poly` keyed by each enum value.
 *   - **`oneOf` / `anyOf`** — Encoded as `DefType.Poly` whose
 *     branches are the converted alternatives.
 *   - **`allOf`** — Best-effort: the conversion picks the first
 *     branch as the base type. Structural intersection is not
 *     modeled.
 *
 * Anything else falls through to `DefType.Json`. Round-trip is lossy
 * on edge cases JSON Schema features fabric doesn't model (`$ref`,
 * `if`/`then`/`else`, `dependentSchemas`) but covers the shapes
 * typical MCP servers advertise.
 */
object JsonSchemaToDefinition {

  def apply(schema: Json): Definition = toDefinition(schema)

  private def toDefinition(schema: Json): Definition = {
    val description = schema.get("description").map(_.asString)
    val format      = schema.get("format").map(_.asString).map(parseFormat).getOrElse(Format.Raw)
    val constraints = parseConstraints(schema)
    val defType     = toDefType(schema)
    Definition(defType = defType, description = description, format = format, constraints = constraints)
  }

  private def toDefType(schema: Json): DefType = schema match {
    case _: Obj =>
      // Composition keywords take precedence over `type`.
      schema.get("oneOf").orElse(schema.get("anyOf")) match {
        case Some(arr: Arr) => return buildPoly(arr.value.toList)
        case _              => ()
      }
      schema.get("allOf") match {
        case Some(arr: Arr) if arr.value.nonEmpty =>
          // `allOf` is structural intersection; fabric doesn't model that. Pick the head.
          return toDefinition(arr.value.head).defType
        case _ => ()
      }
      schema.get("type").map(_.asString) match {
        case Some("object")  => objectType(schema)
        case Some("string")  => DefType.Str
        case Some("integer") => DefType.Int
        case Some("number")  => DefType.Dec
        case Some("boolean") => DefType.Bool
        case Some("null")    => DefType.Null
        case Some("array") =>
          val items = schema.get("items").getOrElse(Obj.empty)
          DefType.Arr(toDefinition(items))
        case Some(_) => DefType.Json
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

  private def buildPoly(alternatives: List[Json]): DefType = {
    val branches = alternatives.zipWithIndex.map { case (alt, i) =>
      val key = alt.get("title").map(_.asString)
        .orElse(alt.get("type").map(_.asString))
        .getOrElse(s"alt$i")
      key -> toDefinition(alt)
    }
    DefType.Poly(VectorMap.from(branches))
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

  private def parseConstraints(schema: Json): Constraints = {
    def asInt(j: Json): Option[Int] = j match {
      case n: NumInt => Some(n.value.toInt)
      case n: NumDec => Some(n.value.toInt)
      case _         => None
    }
    def asDouble(j: Json): Option[Double] = j match {
      case n: NumInt => Some(n.value.toDouble)
      case n: NumDec => Some(n.value.toDouble)
      case _         => None
    }
    Constraints(
      pattern          = schema.get("pattern").collect { case Str(s, _) => s },
      minLength        = schema.get("minLength").flatMap(asInt),
      maxLength        = schema.get("maxLength").flatMap(asInt),
      minimum          = schema.get("minimum").flatMap(asDouble),
      maximum          = schema.get("maximum").flatMap(asDouble),
      exclusiveMinimum = schema.get("exclusiveMinimum").flatMap(asDouble),
      exclusiveMaximum = schema.get("exclusiveMaximum").flatMap(asDouble),
      multipleOf       = schema.get("multipleOf").flatMap(asDouble),
      minItems         = schema.get("minItems").flatMap(asInt),
      maxItems         = schema.get("maxItems").flatMap(asInt),
      uniqueItems      = schema.get("uniqueItems").collect { case Bool(b, _) => b }
    )
  }

  private def parseFormat(name: String): Format = name match {
    case "date-time" => Format.DateTime
    case "date"      => Format.Date
    case "time"      => Format.Time
    case "email"     => Format.Email
    case "uri"       => Format.Uri
    case "uuid"      => Format.Uuid
    case "hostname"  => Format.Hostname
    case "ipv4"      => Format.Ipv4
    case "ipv6"      => Format.Ipv6
    case _           => Format.Raw
  }
}
