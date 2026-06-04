package sigil.tool

import fabric.*
import fabric.define.{DefType, Definition, Format}
import fabric.rw.*

/**
 * Single derivation surface for everything the framework needs to know about
 * a tool input's wire shape. Each tool builds one `WireSurface[Input]` and
 * the four downstream concerns — schema emission, example synthesis, JSON
 * normalisation, and end-to-end decode — all read from one shared walk
 * of the `Definition`.
 *
 * Replaces three previously-independent derivations:
 *   - [[DefinitionToSchema]] (schema emit) — singleton-poly leaf rendering,
 *     `Json` field handling, polymorphic discriminator rules.
 *   - [[InputNormalizer]] (pre-decode coercion) — empty-string Opt(Str) →
 *     null, literal `"null"` → null, string-encoded scalars, singleton-poly
 *     leaf canonicalisation.
 *   - [[RefusalPayload.synthesizeExample]] (example payload) — placeholder
 *     synthesis for refusal bodies.
 *
 * The three previously-divergent rule sets are now defined once on a shared
 * walker and reused everywhere — that's the structural property that closes
 * the class of "schema and decoder drifted" bugs.
 *
 * `decode` collects multi-field violations in one pass instead of one
 * round-trip per violation. See [[DecodeError]].
 */
trait WireSurface[T] {

  /**
   * Strict JSON Schema sent to the LLM (provider dialects post-process
   * via `sigil.provider.StrictSchema` adapters as needed).
   */
  def schema: fabric.Json

  /**
   * A valid invocation payload for refusal bodies. Drawn from an
   * authored example when available; otherwise synthesised from the
   * schema's required fields with placeholder values matching each
   * field's declared type.
   */
  def example: fabric.Json

  /**
   * Pre-decode JSON-to-JSON coercion. Same rewrites
   * [[InputNormalizer.normalize]] applied — exposed here so callers
   * needing only the normalised form (without the full materialise
   * step) skip the `decode` overhead.
   */
  def normalize(raw: fabric.Json): fabric.Json

  /**
   * End-to-end decode: normalise → validate → materialise. On success,
   * the typed `T`. On failure, every violation surfaced at once.
   */
  def decode(raw: fabric.Json): Either[DecodeError, T]
}

object WireSurface {

  /**
   * Build a surface from a tool. Reads [[Tool.inputDefinition]] and
   * [[Tool.inputRW]]; biases the example payload to the tool's first
   * authored [[ToolExample]] when present.
   */
  def fromTool(tool: Tool): WireSurface[tool.Input] =
    new Impl[tool.Input](
      definition = tool.inputDefinition,
      rw = tool.inputRW,
      authoredExample = tool.examples.headOption.map(_.input.asInstanceOf[tool.Input])
    )

  /**
   * Build a surface from a raw `Definition` + `RW`. Used by codegen,
   * tests, and any caller without a surrounding `Tool`.
   */
  def fromDefinition[T](definition: Definition, rw: RW[T]): WireSurface[T] =
    new Impl[T](definition, rw, authoredExample = None)

  // ---- Definition walkers — single source of truth -------------------

  /**
   * True when a poly subtype's defType carries no structural payload —
   * `DefType.Null` (Scala 3 enum case / sealed-trait case object via
   * `RW.enumeration`) or `DefType.Obj` with an empty field map
   * (`RW.static`-backed open `PolyType` registration). The schema
   * emitter, normalizer, and example synthesizer all key off this
   * predicate — `WireSurface` is its only home, the three downstream
   * concerns read it from here so they cannot drift.
   */
  private[tool] def isSingletonShape(d: Definition): Boolean = d.defType match {
    case DefType.Null => true
    case DefType.Obj(m) => m.isEmpty
    case _ => false
  }

  /**
   * Tail segment of a fabric poly key — `"Complexity.Medium"` → `"Medium"`,
   * `"Outer.Inner.Leaf"` → `"Leaf"`, a bare `"Medium"` → `"Medium"`.
   */
  private[tool] def leafOf(key: String): String = {
    val i = key.lastIndexOf('.')
    if (i < 0) key else key.substring(i + 1)
  }

  /**
   * True when the definition tree contains a `DefType.Json` anywhere.
   * The provider-side strict-mode gate calls this through the existing
   * [[DefinitionToSchema.containsJson]] alias.
   */
  def containsJson(definition: Definition): Boolean = definition.defType match {
    case DefType.Json => true
    case DefType.Opt(t) => containsJson(t)
    case DefType.Arr(t) => containsJson(t)
    case DefType.Obj(map) => map.values.exists(containsJson)
    case DefType.Poly(values, _) => values.values.exists(containsJson)
    case _ => false
  }

  // ---- Schema emitter ------------------------------------------------

  /**
   * Discriminator key used for polymorphic discrimination on the wire.
   */
  val Discriminator: String = "type"

  private[tool] def emitSchema(definition: Definition): fabric.Json = {
    val base = convertDefType(definition.defType)
    val withDesc = definition.description.fold(base)(d => base.merge(obj("description" -> str(d))))
    val withFormat =
      if (definition.format == Format.Raw) withDesc
      else withDesc.merge(obj("format" -> str(definition.format.name)))
    withConstraints(withFormat, definition.constraints)
  }

  private def withConstraints(schema: fabric.Json, c: fabric.define.Constraints): fabric.Json =
    if (c.isEmpty) schema
    else {
      val pairs: List[(String, fabric.Json)] = List(
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

  private def convertDefType(defType: DefType): fabric.Json = defType match {
    case DefType.Str => obj("type" -> str("string"))
    case DefType.Int => obj("type" -> str("integer"))
    case DefType.Dec => obj("type" -> str("number"))
    case DefType.Bool => obj("type" -> str("boolean"))
    case DefType.Null => obj("type" -> str("null"))
    case DefType.Json => obj()
    case DefType.Arr(t) => obj("type" -> str("array"), "items" -> emitSchema(t))
    case DefType.Opt(t) => emitSchema(t)
    case DefType.Obj(map) => objectSchema(map)
    case DefType.Poly(values, _) => polySchema(values)
  }

  private def objectSchema(map: Map[String, Definition]): fabric.Json = {
    val required = map.collect { case (key, d) if !d.isOpt => str(key) }.toList
    obj(
      "type" -> str("object"),
      "properties" -> obj(map.map { case (key, d) => key -> emitSchema(d) }.toList*),
      "required" -> arr(required*),
      "additionalProperties" -> bool(false)
    )
  }

  private def polySchema(values: Map[String, Definition]): fabric.Json = {
    if (values.isEmpty) return obj("type" -> str("string"))
    if (values.values.forall(d => isSingletonShape(d))) {
      obj(
        "type" -> str("string"),
        "enum" -> arr(values.keys.map(k => str(leafOf(k))).toList*)
      )
    } else {
      val branches = values.map { case (name, d) => variantBranch(name, d) }.toList
      obj("oneOf" -> arr(branches*))
    }
  }

  private def variantBranch(name: String, definition: Definition): fabric.Json = {
    val constProp: (String, fabric.Json) =
      Discriminator -> obj("type" -> str("string"), "const" -> str(leafOf(name)))
    definition.defType match {
      case DefType.Obj(map) =>
        val required = str(Discriminator) :: map.collect {
          case (key, d) if !d.isOpt => str(key)
        }.toList
        obj(
          "type" -> str("object"),
          "properties" -> obj(
            (constProp :: map.map { case (key, d) => key -> emitSchema(d) }.toList)*
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
          "properties" -> obj(constProp, "value" -> emitSchema(definition)),
          "required" -> arr(str(Discriminator), str("value")),
          "additionalProperties" -> bool(false)
        )
    }
  }

  // ---- Example synthesizer ------------------------------------------

  private[tool] def synthesizeExample(definition: Definition): fabric.Json =
    synthesizeForType(definition.defType)

  private def synthesizeForType(t: DefType): fabric.Json = t match {
    case DefType.Str => str("<string>")
    case DefType.Int => num(0)
    case DefType.Dec => num(0.0)
    case DefType.Bool => bool(true)
    case DefType.Null => Null
    case DefType.Json => obj()
    case DefType.Arr(item) => arr(synthesizeForType(item.defType))
    case DefType.Opt(inner) => synthesizeForType(inner.defType)
    case DefType.Obj(fields) =>
      Obj(fields.iterator.flatMap { case (k, d) =>
        if (d.isOpt) None else Some(k -> synthesizeForType(d.defType))
      }.toMap)
    case DefType.Poly(values, _) if values.nonEmpty && values.values.forall(d => isSingletonShape(d)) =>
      // Singleton-only poly — schema advertises a flat string enum;
      // the example must match (closes #309's intent: example and
      // schema shapes share the same source).
      str(leafOf(values.head._1))
    case DefType.Poly(values, _) =>
      values.headOption match {
        case Some((discValue, branchDef)) =>
          val branchObj = synthesizeForType(branchDef.defType) match {
            case Obj(map) => map
            case other => Map("value" -> other)
          }
          Obj(branchObj.updated(Discriminator, str(leafOf(discValue))))
        case None => obj()
      }
  }

  // ---- Normalizer ----------------------------------------------------

  private val IntegerPattern = "^-?\\d+$".r
  private val NumberPattern = "^-?\\d+(?:\\.\\d+)?$".r

  private[tool] def normalize(json: fabric.Json, defType: DefType): fabric.Json = defType match {
    case DefType.Obj(fieldMap) =>
      json match {
        case Obj(values) =>
          val rewritten = values.map { case (key, value) =>
            fieldMap.get(key) match {
              case Some(fieldDef) => key -> normalize(value, fieldDef.defType)
              case None => key -> value
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
      json match {
        case Str(s, _) if s.isEmpty && inner.defType == DefType.Str => Null
        case Str("null", _) if inner.defType == DefType.Str => Null
        case other => normalize(other, inner.defType)
      }

    case DefType.Int =>
      coerceStringScalar(json, intParse = true, numParse = false, boolParse = false)

    case DefType.Dec =>
      coerceStringScalar(json, intParse = false, numParse = true, boolParse = false)

    case DefType.Bool =>
      coerceStringScalar(json, intParse = false, numParse = false, boolParse = true)

    case DefType.Poly(values, _) =>
      if (values.nonEmpty && values.values.forall(d => isSingletonShape(d)))
        normalizeSingletonPoly(json, values)
      else json

    case _ => json
  }

  private def normalizeSingletonPoly(json: fabric.Json, values: Map[String, Definition]): fabric.Json = {
    val leafIndex: Map[String, List[String]] =
      values.keys.toList.groupBy(leafOf)
    val nullKey: String => Boolean = k => values.get(k).exists(_.defType == DefType.Null)

    def resolveByLeaf(raw: String): Option[String] =
      if (values.contains(raw)) Some(raw)
      else leafIndex.get(leafOf(raw)) match {
        case Some(single :: Nil) => Some(single)
        case _ => None
      }

    def canonicalize(raw: String): fabric.Json = resolveByLeaf(raw) match {
      case Some(k) if nullKey(k) => Str(k)
      case Some(k) => Obj(Map("type" -> Str(k)))
      case None => Str(raw)
    }

    json match {
      case Str(s, _) =>
        canonicalize(s)
      case Obj(fields) =>
        fields.get("type") match {
          case Some(Str(s, _)) =>
            resolveByLeaf(s) match {
              case Some(k) => Obj(fields.updated("type", Str(k)))
              case None => json
            }
          case _ => json
        }
      case other => other
    }
  }

  private def coerceStringScalar(json: fabric.Json,
                                 intParse: Boolean,
                                 numParse: Boolean,
                                 boolParse: Boolean): fabric.Json =
    json match {
      case Str("null", _) => Null
      case Str(s, _) if intParse && IntegerPattern.matches(s) =>
        scala.util.Try(NumInt(s.toLong)).getOrElse(json)
      case Str(s, _) if numParse && NumberPattern.matches(s) =>
        scala.util.Try(NumDec(BigDecimal(s))).getOrElse(json)
      case Str(s, _) if boolParse && s.equalsIgnoreCase("true") => Bool(true)
      case Str(s, _) if boolParse && s.equalsIgnoreCase("false") => Bool(false)
      case other => other
    }

  // ---- Schema-shape summary (for materialise diagnostics) ----------

  private[tool] def summarizeShape(definition: Definition): String =
    definition.defType match {
      case DefType.Obj(fields) =>
        val required = fields.iterator.collect {
          case (k, d) if !d.isOpt => s"$k: ${typeName(d.defType)}"
        }.toList
        val optional = fields.iterator.collect {
          case (k, d) if d.isOpt => s"$k: ${typeName(d.defType)}"
        }.toList
        val parts = List(
          if (required.nonEmpty) Some(s"required: [${required.mkString(", ")}]") else None,
          if (optional.nonEmpty) Some(s"optional: [${optional.mkString(", ")}]") else None
        ).flatten
        if (parts.isEmpty) "{}" else parts.mkString("{ ", "; ", " }")
      case DefType.Json => "(any JSON value)"
      case other => s"<${typeName(other)}>"
    }

  private def typeName(defType: DefType): String = defType match {
    case DefType.Str => "string"
    case DefType.Int => "integer"
    case DefType.Dec => "number"
    case DefType.Bool => "boolean"
    case DefType.Null => "null"
    case DefType.Json => "any"
    case DefType.Arr(t) => s"array<${typeName(t.defType)}>"
    case DefType.Opt(t) => typeName(t.defType)
    case DefType.Obj(_) => "object"
    case DefType.Poly(_, _) => "oneOf"
  }

  // ---- Impl ----------------------------------------------------------

  final private class Impl[T](definition: Definition,
                              rw: RW[T],
                              authoredExample: Option[T])
    extends WireSurface[T] {
    lazy val schema: fabric.Json = emitSchema(definition)

    lazy val example: fabric.Json = authoredExample match {
      case Some(t) =>
        try rw.read(t)
        catch { case _: Throwable => synthesizeExample(definition) }
      case None =>
        synthesizeExample(definition)
    }

    def normalize(raw: fabric.Json): fabric.Json =
      WireSurface.normalize(raw, definition.defType)

    def decode(raw: fabric.Json): Either[DecodeError, T] = {
      val normalised = normalize(raw)
      val violations = ToolInputValidator
        .validate(normalised, definition)
        .map(DecodeViolation.parse)
      if (violations.nonEmpty) {
        // Try materialise too — if it ALSO throws, fold the throw into
        // the violations list so the agent sees both the constraint
        // problems and the structural problem at once.
        val materializeErr =
          try { rw.write(normalised); None }
          catch { case t: Throwable => Some(materializeViolation(t)) }
        Left(DecodeError(violations ++ materializeErr.toList, raw))
      } else
        try Right(rw.write(normalised))
        catch {
          case t: Throwable =>
            Left(DecodeError(List(materializeViolation(t)), raw))
        }
    }

    private def materializeViolation(t: Throwable): DecodeViolation = {
      val errorClass = t.getClass.getSimpleName
      val errorMessage = Option(t.getMessage).filter(_.nonEmpty).getOrElse("(no message)")
      DecodeViolation(
        path = Nil,
        reason = s"$errorClass: $errorMessage. Expected shape: ${summarizeShape(definition)}"
      )
    }
  }
}
