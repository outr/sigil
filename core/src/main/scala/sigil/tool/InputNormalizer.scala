package sigil.tool

import fabric.*
import fabric.define.{DefType, Definition}

/**
 * Normalises JSON tool-call arguments before they're handed to
 * fabric's `RW.write` for typed materialisation. Bug #58.
 *
 * Specific concern today: an `Option[String]` parameter renders as
 * `{"type":"string"}` (omittable, but when present must be string —
 * `null` is not a permitted value because the schema doesn't list
 * a null variant). Under grammar-constrained generation, the model
 * has two schema-valid encodings of "no value":
 *
 *   1. Omit the key.
 *   2. Emit `""` (the empty string is a valid string).
 *
 * The choice is stochastic — the same model picks one form on one
 * turn, the other on the next. When the model picks `""`, fabric's
 * decoder produces `Some("")`. Tools that follow the natural Scala
 * idiom `input.field.orElse(default)` then silently get a non-empty
 * `Option`, the `orElse` branch doesn't fire, and downstream code
 * uses `""` as a real value.
 *
 * This normaliser walks the JSON alongside the input's [[Definition]]
 * and rewrites `""` → `Null` when the corresponding definition slot
 * is `Opt(Str)`. Strings that are NOT optional (required string
 * fields) pass through unchanged — the framework doesn't second-
 * guess a deliberately-empty required string.
 *
 * Recurses through nested `Obj` and `Arr` types so deeply-nested
 * `Option[String]` fields normalise consistently.
 */
object InputNormalizer {

  /** Walk `json` alongside `definition` and rewrite empty-string
    * Option fields to `Null`. Returns the normalised JSON. */
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
      // Bug #58 — empty-string-as-None coercion only applies when
      // the wrapper is Opt and the inner type is Str. Other Opt
      // wrappers (Opt(Int), Opt(Obj(...))) keep their values
      // unchanged; the "" → None idiom is specific to strings.
      json match {
        case Str(s, _) if s.isEmpty && inner.defType == DefType.Str => Null
        case other                                                  => normalize(other, inner.defType)
      }

    case _ => json
  }
}
