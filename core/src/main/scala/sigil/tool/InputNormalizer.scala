package sigil.tool

import fabric.*
import fabric.define.{DefType, Definition}

/**
 * Normalises JSON tool-call arguments before they're handed to
 * fabric's `RW.write` for typed materialisation.
 *
 * Two coercion families, both walking the JSON alongside the input's
 * [[Definition]] so the rewrite is schema-aware:
 *
 *   - **Sigil #58 — empty-string-as-None.** An `Option[String]` field's
 *     schema is `{"type":"string"}` (omittable, but when present must
 *     be string — `null` isn't a permitted value because the schema
 *     doesn't list a null variant). Under grammar-constrained generation
 *     the model has two schema-valid encodings of "no value": omit the
 *     key, or emit `""`. When it picks `""`, fabric decodes `Some("")`
 *     and tools using `input.field.orElse(default)` get a non-empty
 *     `Option`. Rewrite `Str("") -> Null` when the field is `Opt(Str)`.
 *
 *   - **Sigil #272 — string-encoded scalars.** Small/cheap models
 *     (Haiku 4.5 in particular) frequently encode integer / number /
 *     boolean fields as quoted strings — `themeId: "168594932069"`,
 *     `themeId: "null"`, `enabled: "true"`. The schema declares
 *     `integer` / `number` / `boolean`, so fabric's RW rejects the
 *     string. Rewrite:
 *
 *       `Str("null")`           → `Null`   (any non-string field)
 *       `Str("-?\d+")`          → `NumInt` (when field is `Int`)
 *       `Str("-?\d+(\.\d+)?")`  → `NumDec` (when field is `Dec`)
 *       `Str("true" / "false")` → `Bool`   (when field is `Bool`,
 *                                           case-insensitive)
 *
 *     Same shape OpenAI's structured-outputs SDK applies. Scoped to
 *     fields whose schema explicitly declares the target type — string
 *     fields keep their values verbatim.
 *
 * Recurses through `Obj`, `Arr`, and `Opt` so deeply-nested fields
 * normalise consistently.
 */
object InputNormalizer {

  private val IntegerPattern = "^-?\\d+$".r
  private val NumberPattern  = "^-?\\d+(?:\\.\\d+)?$".r

  /** Walk `json` alongside `definition` and apply both coercion families.
    * Returns the normalised JSON. */
  def normalize(json: Json, definition: Definition): Json =
    normalize(json, definition.defType)

  private def normalize(json: Json, defType: DefType): Json = defType match {
    case DefType.Obj(fieldMap) =>
      json match {
        case Obj(values) =>
          val rewritten = values.map { case (key, value) =>
            fieldMap.get(key) match {
              case Some(fieldDef) => key -> normalize(value, fieldDef.defType)
              case None           => key -> value
            }
          }
          Obj(rewritten)
        case other => other
      }

    case DefType.Arr(elementDef) =>
      json match {
        case Arr(values, _) =>
          Arr(values.map(v => normalize(v, elementDef.defType)))
        case other => other
      }

    case DefType.Opt(inner) =>
      // The wrapper recurses into the inner type so every scalar
      // coercion below also applies to nullable variants. Bug #58's
      // empty-string-as-None remains the only Opt-specific rewrite —
      // emit `Null` when the inner is `Str` and the value is empty;
      // for other inner types the scalar coercions handle non-empty
      // string encodings of nullability via `Str("null") -> Null`.
      json match {
        case Str(s, _) if s.isEmpty && inner.defType == DefType.Str => Null
        case other                                                  => normalize(other, inner.defType)
      }

    case DefType.Int =>
      coerceStringScalar(json, intParse = true, numParse = false, boolParse = false)

    case DefType.Dec =>
      coerceStringScalar(json, intParse = false, numParse = true, boolParse = false)

    case DefType.Bool =>
      coerceStringScalar(json, intParse = false, numParse = false, boolParse = true)

    case _ => json
  }

  /** Sigil #272 — rewrite a string-encoded scalar to its declared type
    * when the literal matches the expected shape. Non-string inputs and
    * strings that don't match the expected shape pass through unchanged
    * so fabric's RW still produces an actionable error rather than a
    * silently-wrong coerced value. */
  private def coerceStringScalar(json: Json, intParse: Boolean, numParse: Boolean, boolParse: Boolean): Json =
    json match {
      case Str("null", _) => Null
      case Str(s, _) if intParse && IntegerPattern.matches(s) =>
        scala.util.Try(NumInt(s.toLong)).getOrElse(json)
      case Str(s, _) if numParse && NumberPattern.matches(s) =>
        scala.util.Try(NumDec(BigDecimal(s))).getOrElse(json)
      case Str(s, _) if boolParse && s.equalsIgnoreCase("true")  => Bool(true)
      case Str(s, _) if boolParse && s.equalsIgnoreCase("false") => Bool(false)
      case other => other
    }
}
