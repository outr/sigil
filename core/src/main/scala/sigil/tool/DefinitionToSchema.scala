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

  /**
   * Bug #64 — true if the schema tree rooted at `definition` contains a
   * `DefType.Json` anywhere. Walks through `Opt`, `Arr`, `Obj`, and
   * `Poly` recursively. Used by `OpenAIProvider` to decide whether a
   * tool can ship with `strict: true`: OpenAI's strict mode demands
   * every "object" branch carry closed `properties` +
   * `additionalProperties: false`, which is mutually exclusive with
   * "any JSON value" — strict and `Json` can't coexist, so any tool
   * whose input contains a `Json` field opts out of strict per-tool.
   */
  def containsJson(definition: Definition): Boolean = definition.defType match {
    case DefType.Json            => true
    case DefType.Opt(t)          => containsJson(t)
    case DefType.Arr(t)          => containsJson(t)
    case DefType.Obj(map)        => map.values.exists(containsJson)
    case DefType.Poly(values, _) => values.values.exists(containsJson)
    case _                       => false
  }

  private def convert(definition: Definition): Json = {
    val base = convertDefType(definition.defType)
    val withDesc = definition.description.fold(base)(d => base.merge(obj("description" -> str(d))))
    val withFormat =
      if (definition.format == Format.Raw) withDesc
      else withDesc.merge(obj("format" -> str(definition.format.name)))
    withConstraints(withFormat, definition.constraints)
  }

  private def withConstraints(schema: Json, c: fabric.define.Constraints): Json =
    if (c.isEmpty) schema
    else {
      val pairs: List[(String, Json)] = List(
        c.pattern.map("pattern" -> str(_)),
        c.minLength.map("minLength" -> num(_)),
        c.maxLength.map("maxLength" -> num(_)),
        c.minimum.map("minimum" -> num(_)),
        c.maximum.map("maximum" -> num(_)),
        c.exclusiveMinimum.map("exclusiveMinimum" -> num(_)),
        c.exclusiveMaximum.map("exclusiveMaximum" -> num(_)),
        c.multipleOf.map("multipleOf" -> num(_)),
        c.minItems.map("minItems" -> num(_)),
        c.maxItems.map("maxItems" -> num(_)),
        c.uniqueItems.map("uniqueItems" -> bool(_))
      ).flatten
      if (pairs.isEmpty) schema else schema.merge(obj(pairs*))
    }

  private def convertDefType(defType: DefType): Json =
    defType match {
      case DefType.Str => obj("type" -> str("string"))
      case DefType.Int => obj("type" -> str("integer"))
      case DefType.Dec => obj("type" -> str("number"))
      case DefType.Bool => obj("type" -> str("boolean"))
      case DefType.Null => obj("type" -> str("null"))
      case DefType.Json =>
        // Empty schema = "any JSON value" — the canonical JSON-Schema
        // shape for an unconstrained value, accepted by every
        // provider Sigil targets.
        //
        // History (bug #65 reverts #63): the original concern was
        // that OpenAI's strict-mode validator rejects `{}` inside an
        // `anyOf` (which `Option[Json]` produces). #63 tried to fix
        // that by emitting a typed permissive union
        // (`type: ["string","number","integer","boolean","object","array","null"]`).
        // That shape turned out to be rejected by OpenAI in both
        // modes — non-strict still demands structural details for
        // the `array` and `object` branches that "any JSON" can't
        // supply. #64 solved the underlying issue properly by
        // disabling strict mode for tools whose input contains
        // `DefType.Json`; once strict is off, `{}` is accepted again.
        // Empty stays.
        obj()
      case DefType.Arr(t) =>
        obj("type" -> str("array"), "items" -> convert(t))
      case DefType.Opt(t) => convert(t)
      case DefType.Obj(map) =>
        objectSchema(map)
      case DefType.Poly(values, _) =>
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
    // Empty poly — no subtypes registered. An `enum: []` or `oneOf: []`
    // is invalid JSON Schema; stricter provider validators (notably
    // OpenAI gpt-4o-mini) reject the whole tools array. Render as an
    // unconstrained string so the field remains typed but unvalidated.
    if (values.isEmpty) return obj("type" -> str("string"))
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
    // Discriminator schema: `type: "string"` + `const: <name>`. The
    // `type` key is mandatory for OpenAI strict mode (rejects schemas
    // missing it); harmless and accurate for non-strict providers.
    val constProp: (String, Json) =
      Discriminator -> obj("type" -> str("string"), "const" -> str(name))
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
