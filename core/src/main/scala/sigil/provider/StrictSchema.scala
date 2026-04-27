package sigil.provider

import fabric.*

/**
 * Provider-specific JSON-schema rewrites for tool-call args.
 *
 * Each provider's grammar-constrained decoder (or schema validator)
 * accepts a different subset of JSON-Schema keywords. Sigil keeps the
 * full annotations (`@pattern`, `@format`, numeric bounds, …) on the
 * Scala types — the post-decode [[sigil.tool.ToolInputValidator]]
 * re-checks every constraint regardless of provider — but on the wire
 * we send each provider exactly what it accepts. Stripping more than
 * necessary loses real generation-time enforcement (e.g., dropping
 * `pattern` for llama.cpp lets the model emit content that violates
 * the pattern, only caught post-decode).
 *
 * Use the right per-provider helper at the call site:
 *
 *   - [[forOpenAIStrict]]  — OpenAI Responses with `strict: true`
 *                            (and DeepSeek, which mirrors OpenAI's
 *                             strict-mode dialect via [[forDeepSeek]]).
 *   - [[forGemini]]        — Gemini function calling (rejects
 *                            `additionalProperties` and the
 *                            unsupported keywords).
 *   - [[forAnthropic]]     — Anthropic (no strict-mode equivalent;
 *                            keep the schema clean of grammar-only
 *                            keywords for hygiene).
 *
 * llama.cpp's chat-completions endpoint translates the FULL schema
 * (including `pattern` / `format` / numeric bounds) into a GBNF
 * grammar — pass `DefinitionToSchema(input)` directly without any
 * helper from this object.
 */
object StrictSchema {

  /** Keywords that strict-mode decoders (OpenAI strict, DeepSeek,
    * Gemini) reject because they constrain character-level content
    * that doesn't compose with token-level sampling. Sigil keeps these
    * on the Scala types for [[sigil.tool.ToolInputValidator]]'s
    * post-decode check; on the wire they're stripped per provider. */
  val UnsupportedKeys: Set[String] = Set(
    "pattern", "format",
    "minLength", "maxLength",
    "minimum", "maximum",
    "exclusiveMinimum", "exclusiveMaximum",
    "multipleOf",
    "minItems", "maxItems",
    "uniqueItems"
  )

  /** OpenAI Responses with `strict: true`: every property becomes
    * required (optionals widened to nullable), `additionalProperties:
    * false`, and grammar-incompatible keywords stripped. Strict mode
    * enables full grammar-constrained decoding — the model can't emit
    * JSON that doesn't match. */
  def forOpenAIStrict(schema: Json): Json = transform(schema)

  /** DeepSeek mirrors OpenAI's strict-mode dialect — same transforms. */
  def forDeepSeek(schema: Json): Json = forOpenAIStrict(schema)

  /** Gemini natively grammar-constrains function-call args but rejects
    * the unsupported keywords AND `additionalProperties` (any value).
    * Required fields stay required (Gemini doesn't enforce
    * "everything required" the way OpenAI strict does); just clean
    * up the keys Gemini doesn't tolerate. */
  def forGemini(schema: Json): Json =
    stripAdditionalProperties(stripUnsupportedKeys(schema))

  /** Anthropic doesn't grammar-constrain tool-call args at the schema
    * level. The post-decode validator is the real safety net. We strip
    * grammar-only keywords for schema hygiene (Anthropic's API tolerates
    * unknown keys but the cleaner shape avoids confusing surface area). */
  def forAnthropic(schema: Json): Json = stripUnsupportedKeys(schema)

  /** Recursively strip [[UnsupportedKeys]] from a JSON Schema without
    * altering its required / property / object shape. Building block
    * for the per-provider helpers; exposed here so apps with custom
    * provider implementations can compose their own rewrites. */
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

  /** Recursively remove `additionalProperties` (any value) from a
    * schema. Gemini's validator rejects schemas containing it. */
  def stripAdditionalProperties(json: Json): Json = json match {
    case Obj(map) =>
      val kept = map.iterator.collect {
        case (k, v) if k != "additionalProperties" => k -> stripAdditionalProperties(v)
      }.toList
      obj(kept*)
    case Arr(items, _) =>
      arr(items.map(stripAdditionalProperties)*)
    case other => other
  }

  // -- internal: full strict-mode transform ----------------------------------

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
          obj("anyOf" -> arr(schema, obj("type" -> str("null"))))
      }
    case other => obj("anyOf" -> arr(other, obj("type" -> str("null"))))
  }
}
