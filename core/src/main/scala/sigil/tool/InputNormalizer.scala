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
 *   - **Sigil #279 — literal `"null"` on Opt(Str).** Same model habit
 *     extended to the string side: when the schema is `["string","null"]`,
 *     Haiku (and friends) emit the three-character word `"null"` to mean
 *     "no value" instead of JSON null. Rewrite `Str("null") → Null`
 *     when the field is `Opt(Str)`. Required-string fields keep
 *     `"null"` verbatim.
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
      // coercion below also applies to nullable variants.
      //
      //   - Bug #58: empty-string `""` on Opt(Str) → Null (model encoded
      //     "no value" as the empty string under grammar-constrained
      //     generation when the schema was non-nullable string).
      //   - Bug #279: literal three-character `"null"` on Opt(Str) →
      //     Null (Haiku-style: the schema is `["string","null"]`, and
      //     the model interprets "this can be null" as "I should say
      //     the WORD null" rather than emit JSON null).
      //
      // Both gated on `inner.defType == DefType.Str` so required-string
      // fields (`DefType.Str` without an Opt wrapper) keep their values
      // verbatim — `"null"` on a required string is a real value the
      // agent meant to pass.
      json match {
        case Str(s, _) if s.isEmpty && inner.defType == DefType.Str => Null
        case Str("null", _) if inner.defType == DefType.Str         => Null
        case other                                                  => normalize(other, inner.defType)
      }

    case DefType.Int =>
      coerceStringScalar(json, intParse = true, numParse = false, boolParse = false)

    case DefType.Dec =>
      coerceStringScalar(json, intParse = false, numParse = true, boolParse = false)

    case DefType.Bool =>
      coerceStringScalar(json, intParse = false, numParse = false, boolParse = true)

    case DefType.Poly(values, _) =>
      // Singleton-only hierarchies advertise a flat string `enum` of leaf
      // names (see `DefinitionToSchema.polySchema`). Canonicalise the
      // wire input back to whatever fabric's RW expects so older
      // persisted records and in-flight clients keep round-tripping:
      //   - Scala 3 enum / `RW.enumeration` (every subtype `DefType.Null`)
      //     decodes a bare `Str(<simpleClassName>)`.
      //   - Open `PolyType` with `RW.static`-backed registrations (every
      //     subtype `DefType.Obj(empty)`) decodes `Obj("type" -> Str(...))`.
      // Accepts the leaf form (`"Medium"`, `"AnalysisWork"`), any
      // shorter prefix-stripped form (`"Complexity.Medium"`), and the
      // original wire form. Mixed / fields-bearing hierarchies pass
      // through unchanged so the existing `oneOf` decoder keeps owning
      // them.
      if (values.nonEmpty && values.values.forall(isSingletonShape))
        normalizeSingletonPoly(json, values)
      else json

    case _ => json
  }

  /** Same predicate as [[DefinitionToSchema.isSingletonShape]] — kept local so
    * the two layers don't reach across the codegen boundary for one line. */
  private def isSingletonShape(d: Definition): Boolean = d.defType match {
    case DefType.Null   => true
    case DefType.Obj(m) => m.isEmpty
    case _              => false
  }

  /** Canonicalise an input value addressed at a singleton-only poly field
    * back to fabric's wire shape — bare-Str for Null subtypes, Obj-with-
    * `type` for Obj-empty subtypes — looking up the registered key whose
    * leaf segment (text after the last `.`) matches what came in.
    *
    * Falls through unchanged when the leaf is ambiguous (two registered
    * keys share the same leaf segment) or when the canonical wire form
    * already came in: fabric's RW handles those paths. */
  private def normalizeSingletonPoly(json: Json, values: Map[String, Definition]): Json = {
    val leafIndex: Map[String, List[String]] =
      values.keys.toList.groupBy(leafOf)
    val nullKey: String => Boolean = k => values.get(k).exists(_.defType == DefType.Null)

    def resolveByLeaf(raw: String): Option[String] =
      if (values.contains(raw)) Some(raw)
      else leafIndex.get(leafOf(raw)) match {
        case Some(single :: Nil) => Some(single)
        case _                   => None
      }

    def canonicalize(raw: String): Json = resolveByLeaf(raw) match {
      case Some(k) if nullKey(k) => Str(k)
      case Some(k)               => Obj(Map("type" -> Str(k)))
      case None                  => Str(raw)
    }

    json match {
      case Str(s, _) =>
        canonicalize(s)
      case Obj(fields) =>
        // Object form (`{"type": "..."}` or a richer shape). When the
        // discriminator is present, canonicalise it; otherwise leave the
        // object intact and let fabric's RW emit its actionable error.
        fields.get("type") match {
          case Some(Str(s, _)) =>
            resolveByLeaf(s) match {
              case Some(k) => Obj(fields.updated("type", Str(k)))
              case None    => json
            }
          case _ => json
        }
      case other => other
    }
  }

  private def leafOf(name: String): String = {
    val i = name.lastIndexOf('.')
    if (i < 0) name else name.substring(i + 1)
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
