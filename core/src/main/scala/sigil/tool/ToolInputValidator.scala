package sigil.tool

import fabric.*
import fabric.define.{Constraints, DefType, Definition}

import java.util.regex.Pattern

/**
 * Walks a parsed JSON tree alongside its [[Definition]] and reports
 * any constraint violations as structured [[DecodeViolation]]s. The
 * decode path runs this between `JsonParser` and `inputRW.write` for
 * every tool call so the typed input handed to the executor has
 * already been checked against the schema's `pattern`/length/numeric/
 * array bounds.
 *
 * Why post-decode (not pre-write): some constraints — most importantly
 * `pattern` — get stripped from the JSON Schema sent to OpenAI strict
 * mode (the constrained decoder doesn't support character-level regex
 * over BPE tokens). Without re-validation, a model under strict mode
 * can produce a value that's structurally valid JSON but violates
 * the regex on a content field. This validator closes that gap and
 * also helps non-strict providers (Anthropic, Google) where the
 * constraints are advisory in the schema only.
 */
object ToolInputValidator {

  /**
   * Validate `json` against `definition`. Returns an empty list when
   * everything passes; otherwise one [[DecodeViolation]] per failed
   * constraint, each carrying its structured field path.
   */
  def validate(json: Json, definition: Definition): List[DecodeViolation] =
    walk(path = Nil, json = json, definition = definition).toList

  private def walk(path: List[String], json: Json, definition: Definition): Vector[DecodeViolation] = {
    val own = checkConstraints(path, json, definition.constraints)
    val descend: Vector[DecodeViolation] = (json, definition.defType) match {
      case (Obj(map), DefType.Obj(fields)) =>
        fields.iterator.flatMap { case (key, fieldDef) =>
          map.get(key) match {
            case Some(value) => walk(path :+ key, value, fieldDef)
            case None => Vector.empty // missing-required is the parser's job
          }
        }.toVector
      case (Arr(items, _), DefType.Arr(itemDef)) =>
        items.iterator.zipWithIndex.flatMap { case (item, i) =>
          walk(path :+ s"[$i]", item, itemDef)
        }.toVector
      case (j, DefType.Opt(inner)) =>
        if (j == Null) Vector.empty else walk(path, j, inner)
      case _ => Vector.empty
    }
    own ++ descend
  }

  private def checkConstraints(path: List[String], json: Json, c: Constraints): Vector[DecodeViolation] = {
    if (c.isEmpty) return Vector.empty
    val errors = scala.collection.mutable.ListBuffer.empty[String]

    json match {
      case Str(value, _) =>
        c.pattern.foreach { p =>
          val regex = Pattern.compile(p)
          if (!regex.matcher(value).find())
            errors += s"value does not match pattern /$p/"
        }
        c.minLength.foreach(min => if (value.length < min) errors += s"length ${value.length} < minLength $min")
        c.maxLength.foreach(max => if (value.length > max) errors += s"length ${value.length} > maxLength $max")

      case NumDec(value, _) =>
        checkNumeric(value.toDouble, c, errors)
      case NumInt(value, _) =>
        checkNumeric(value.toDouble, c, errors)

      case Arr(items, _) =>
        c.minItems.foreach(min => if (items.length < min) errors += s"${items.length} items < minItems $min")
        c.maxItems.foreach(max => if (items.length > max) errors += s"${items.length} items > maxItems $max")
        c.uniqueItems.foreach { unique =>
          if (unique && items.distinct.length != items.length)
            errors += "array has duplicate items"
        }

      case _ => ()
    }
    errors.toVector.map(reason => DecodeViolation(path, reason, ViolationKind.Constraint))
  }

  private def checkNumeric(value: Double,
                           c: Constraints,
                           errors: scala.collection.mutable.ListBuffer[String]): Unit = {
    c.minimum.foreach(min => if (value < min) errors += s"$value < minimum $min")
    c.maximum.foreach(max => if (value > max) errors += s"$value > maximum $max")
    c.exclusiveMinimum.foreach(min => if (value <= min) errors += s"$value not > exclusiveMinimum $min")
    c.exclusiveMaximum.foreach(max => if (value >= max) errors += s"$value not < exclusiveMaximum $max")
    c.multipleOf.foreach { divisor =>
      if (divisor != 0 && (value % divisor) != 0.0)
        errors += s"$value not a multiple of $divisor"
    }
  }
}
