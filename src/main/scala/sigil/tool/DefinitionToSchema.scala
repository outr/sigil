package sigil.tool

import fabric.*
import fabric.define.{DefType, Definition, Format}

/**
 * Converts a fabric [[Definition]] into strict JSON Schema suitable for LLM
 * structured output and tool calling.
 *
 * Polymorphic types (sealed traits / enums with data) are emitted as discriminated
 * `oneOf` branches using a `"type"` const discriminator. Simple string enums
 * (all case objects) are emitted as `{type: "string", enum: [...]}`.
 *
 * Objects are strict (`additionalProperties: false`); required fields are computed
 * from non-Opt members.
 *
 * Output is standard JSON Schema. Provider-specific dialects (e.g. OpenAI's strict
 * mode requiring every property in `required` with nullable unions for optional
 * fields) should post-process this output.
 */
object DefinitionToSchema {
  val Discriminator: String = "type"

  def apply(definition: Definition): Json = convert(definition)

  private def convert(definition: Definition): Json = {
    val base = convertDefType(definition.defType)
    val withDesc = definition.description.fold(base)(d =>
      base.merge(obj("description" -> str(d)))
    )
    val withFormat =
      if (definition.format == Format.Raw) withDesc
      else withDesc.merge(obj("format" -> str(definition.format.name)))
    withFormat
  }

  private def convertDefType(defType: DefType): Json = defType match {
    case DefType.Str  => obj("type" -> str("string"))
    case DefType.Int  => obj("type" -> str("integer"))
    case DefType.Dec  => obj("type" -> str("number"))
    case DefType.Bool => obj("type" -> str("boolean"))
    case DefType.Null => obj("type" -> str("null"))
    case DefType.Json => obj()
    case DefType.Arr(t) =>
      obj("type" -> str("array"), "items" -> convert(t))
    case DefType.Opt(t) => convert(t)
    case DefType.Obj(map) =>
      objectSchema(map)
    case DefType.Poly(values) =>
      polySchema(values)
  }

  private def objectSchema(map: Map[String, Definition]): Json = {
    val required = map.collect { case (key, d) if !d.isOpt => str(key) }.toList
    obj(
      "type" -> str("object"),
      "properties" -> obj(map.map { case (key, d) => key -> convert(d) }.toList*),
      "required" -> arr(required*),
      "additionalProperties" -> bool(false)
    )
  }

  private def polySchema(values: Map[String, Definition]): Json = {
    val isSimpleEnum = values.values.forall(_.defType == DefType.Null)
    if (isSimpleEnum) {
      obj(
        "type" -> str("string"),
        "enum" -> arr(values.keys.map(str).toList*)
      )
    } else {
      val branches = values.map { case (name, d) => variantBranch(name, d) }.toList
      obj("oneOf" -> arr(branches*))
    }
  }

  private def variantBranch(name: String, definition: Definition): Json = {
    val constProp: (String, Json) = Discriminator -> obj("const" -> str(name))
    definition.defType match {
      case DefType.Obj(map) =>
        val required = str(Discriminator) :: map.collect {
          case (key, d) if !d.isOpt => str(key)
        }.toList
        obj(
          "type" -> str("object"),
          "properties" -> obj(
            (constProp :: map.map { case (key, d) => key -> convert(d) }.toList)*
          ),
          "required" -> arr(required*),
          "additionalProperties" -> bool(false)
        )
      case DefType.Null =>
        obj(
          "type" -> str("object"),
          "properties" -> obj(constProp),
          "required" -> arr(str(Discriminator)),
          "additionalProperties" -> bool(false)
        )
      case _ =>
        obj(
          "type" -> str("object"),
          "properties" -> obj(constProp, "value" -> convert(definition)),
          "required" -> arr(str(Discriminator), str("value")),
          "additionalProperties" -> bool(false)
        )
    }
  }
}