package bench

import fabric.*
import fabric.define.{DefType, Definition}

/**
 * Parses a BFCL function-parameters block (their JSON-Schema-ish
 * dialect) into a fabric [[Definition]] that sigil's provider
 * serializers can emit as a proper function-calling schema.
 *
 * BFCL's `parameters` block shape:
 * {{{
 *   {
 *     "type": "dict",
 *     "properties": {
 *       "base":   {"type": "integer", "description": "..."},
 *       "unit":   {"type": "string",  "description": "...", "default": "units"}
 *     },
 *     "required": ["base"]
 *   }
 * }}}
 *
 * Type values we handle: `integer`, `string`, `number`, `float`,
 * `boolean`, `dict`, `object`, `array`, `list`, `tuple`, `any`.
 * Unknown types collapse to [[DefType.Json]] so the benchmark keeps
 * running rather than crashing on a single outlier.
 */
object BFCLSchemaParser {

  def parse(params: Json): Definition = parseField(params, isOptional = false)

  private def parseField(j: Json, isOptional: Boolean): Definition = {
    val typeStr = j.get("type").map(_.asString).getOrElse("any")
    val description = j.get("description").map(_.asString)
    val base = baseDefType(typeStr, j)
    val wrapped = if (isOptional) DefType.Opt(Definition(base)) else base
    Definition(defType = wrapped, description = description)
  }

  private def baseDefType(typeStr: String, j: Json): DefType = typeStr.toLowerCase match {
    case "integer" | "int" | "long" => DefType.Int
    case "number" | "float" | "double" => DefType.Dec
    case "boolean" | "bool" => DefType.Bool
    case "string" | "str" => DefType.Str
    case "dict" | "object" =>
      val propsJson = j.get("properties").map(_.asObj.value).getOrElse(Map.empty)
      val required = j.get("required").map(_.asVector.map(_.asString).toSet).getOrElse(Set.empty)
      val fields = propsJson.map { case (name, fieldJson) =>
        name -> parseField(fieldJson, isOptional = !required.contains(name))
      }
      DefType.Obj(fields)
    case "array" | "list" | "tuple" =>
      val items = j.get("items").map(parseField(_, isOptional = false)).getOrElse(Definition(DefType.Json))
      DefType.Arr(items)
    case "any" | _ => DefType.Json
  }
}
