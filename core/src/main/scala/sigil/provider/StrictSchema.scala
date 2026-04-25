package sigil.provider

import fabric.*

/**
 * Rewrite a [[sigil.tool.DefinitionToSchema]] output into the dialect
 * OpenAI accepts when a tool is declared with `"strict": true`.
 * Strict mode enables grammar-constrained decoding — the model can't
 * emit JSON that doesn't match the schema, eliminating malformed-args
 * failures (unclosed strings, missing fields, degenerate token loops).
 *
 * Two transforms applied recursively:
 *
 *   1. Every property of every object becomes `required`. Optional
 *      fields are rewritten as a nullable union (`type: [<original>,
 *      "null"]` or `anyOf` for object types) so they remain
 *      semantically optional from the model's perspective — it can
 *      emit `null` when it has nothing to say.
 *   2. Unsupported keywords are stripped: `pattern`, `format`,
 *      `minLength`, `maxLength`, `minimum`, `maximum`,
 *      `exclusiveMinimum`, `exclusiveMaximum`, `multipleOf`,
 *      `minItems`, `maxItems`, `uniqueItems`. Strict mode rejects
 *      schemas with any of these; sigil keeps the annotations on the
 *      Scala types for non-strict providers and for post-decode
 *      validation.
 *
 * `additionalProperties: false` is already emitted by
 * `DefinitionToSchema`; this layer just enforces the property-level
 * rules.
 */
object StrictSchema {

  /** Keywords that grammar-constrained decoders (OpenAI strict mode,
    * Gemini function calling, etc.) reject because they constrain
    * character-level content that doesn't compose with token-level
    * sampling. Sigil keeps these on the Scala types for non-strict
    * use and post-decode validation; on the wire they're stripped. */
  val UnsupportedKeys: Set[String] = Set(
    "pattern", "format",
    "minLength", "maxLength",
    "minimum", "maximum",
    "exclusiveMinimum", "exclusiveMaximum",
    "multipleOf",
    "minItems", "maxItems",
    "uniqueItems"
  )

  /** Recursively strip [[UnsupportedKeys]] from a JSON Schema without
    * altering its required / property / object shape. Suitable for
    * providers (Gemini) that natively grammar-constrain function-call
    * args but reject the unsupported keywords. */
  def stripUnsupportedKeys(schema: Json): Json = schema match {
    case Obj(map) =>
      val cleaned = map.iterator.collect {
        case (k, v) if !UnsupportedKeys.contains(k) => k -> stripUnsupportedKeys(v)
      }.toList
      obj(cleaned*)
    case Arr(items, _) =>
      arr(items.map(stripUnsupportedKeys)*)
    case other => other
  }

  def apply(schema: Json): Json = transform(schema)

  private def transform(json: Json): Json = json match {
    case Obj(map) =>
      val isObject = map.get("type").exists(_ == str("object"))
      if (isObject) transformObject(map.toMap)
      else transformOther(map.toMap)
    case other => other
  }

  /** Rewrite an `{type: "object", properties, required, additionalProperties}`
    * shape: every property becomes required, optionals become nullable. */
  private def transformObject(map: Map[String, Json]): Json = {
    val originalRequired: Set[String] = map.get("required") match {
      case Some(Arr(arr, _)) => arr.collect { case Str(s, _) => s }.toSet
      case _              => Set.empty
    }
    val transformedProps: List[(String, Json)] = map.get("properties") match {
      case Some(Obj(props)) =>
        props.toList.map { case (key, propSchema) =>
          val recursed = transform(propSchema)
          val finalSchema =
            if (originalRequired.contains(key)) recursed
            else makeNullable(recursed)
          key -> finalSchema
        }
      case _ => Nil
    }
    val allKeys = transformedProps.map(_._1)
    val rebuilt = map ++ Map(
      "properties"           -> obj(transformedProps*),
      "required"             -> arr(allKeys.map(str)*),
      "additionalProperties" -> bool(false)
    )
    obj(stripUnsupported(rebuilt).toList*)
  }

  /** Recurse into arrays / oneOf / anyOf branches; strip unsupported
    * keywords. */
  private def transformOther(map: Map[String, Json]): Json = {
    val recursed = map.map {
      case ("items", v)      => "items"      -> transform(v)
      case ("oneOf", Arr(a, _)) => "oneOf"      -> arr(a.map(transform)*)
      case ("anyOf", Arr(a, _)) => "anyOf"      -> arr(a.map(transform)*)
      case ("allOf", Arr(a, _)) => "allOf"      -> arr(a.map(transform)*)
      case kv                => kv
    }
    obj(stripUnsupported(recursed).toList*)
  }

  private def stripUnsupported(map: Map[String, Json]): Map[String, Json] =
    map.filterNot { case (k, _) => UnsupportedKeys.contains(k) }

  /** Wrap a schema so it accepts `null`. For primitive `type` strings we
    * widen to a `[t, "null"]` array; for compound shapes we use
    * `anyOf` so we don't accidentally drop the original constraints. */
  private def makeNullable(schema: Json): Json = schema match {
    case Obj(m) =>
      m.get("type") match {
        case Some(Str(t, _)) if t != "null" =>
          obj((m + ("type" -> arr(str(t), str("null")))).toList*)
        case _ =>
          // For oneOf/anyOf/no-explicit-type schemas, fall back to wrapping.
          obj("anyOf" -> arr(schema, obj("type" -> str("null"))))
      }
    case other => obj("anyOf" -> arr(other, obj("type" -> str("null"))))
  }
}
