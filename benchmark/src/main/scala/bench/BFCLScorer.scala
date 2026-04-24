package bench

import fabric.*

/**
 * Port of BFCL's official AST scorer for the simple_python category.
 * Faithful to `ast_checker.py::simple_function_checker` in the
 * Gorilla repo. Every normalization rule BFCL applies, we apply:
 *
 *   - `standardize_string`: strip ` , . / - _ * ^`, lowercase,
 *     `'` → `"`. Applied to both sides when comparing string params
 *     and string elements inside list/dict values.
 *   - Numeric equality (int ≡ float when values agree).
 *   - Exact list match *after* per-element standardization. Order
 *     sensitivity is handled by the dataset enumerating both orders
 *     in `possible_answer`, not by the scorer.
 *   - Dict match: every key the model provides must be in the
 *     allowed-dict and its value must be in that key's allowed
 *     list; every key the allowed-dict has and the model doesn't
 *     must either have `""` in its allowed list or we fail.
 *   - List-of-dicts: iterate dict-by-dict in order (length must
 *     match). BFCL does not allow reordering here either.
 *   - Missing top-level params: OK iff `""` in the allowed list.
 *
 * What BFCL does NOT do (and we honor by not doing):
 *   - Synonym matching, unit conversion, numeric scale tolerance,
 *     semantic similarity, case/plural normalization beyond the
 *     punctuation-strip rule.
 *
 * Ground-truth shape (one entry per BFCL test case, from
 * `possible_answer/BFCL_v4_*.json`):
 * {{{
 *   {"id": "...", "ground_truth": [ { "func_name": { "param": [allowed, values] } } ] }
 * }}}
 */
object BFCLScorer {

  def scoreSimple(modelCall: (String, Json), groundTruth: Json, required: Set[String] = Set.empty): Boolean = {
    val (modelName, modelArgs) = modelCall
    val gtList = groundTruth.asVector
    gtList.exists { gt =>
      gt.asObj.value.exists { case (funcName, paramMatrix) =>
        if (funcName != modelName) false
        else checkArgs(modelArgs, paramMatrix)
      }
    }
  }

  private def checkArgs(modelArgs: Json, paramMatrix: Json): Boolean = {
    val matrix = paramMatrix.asObj.value.toMap
    val modelMap = scala.util.Try(modelArgs.asObj.value.toMap).getOrElse(Map.empty)

    // Every model-provided arg must appear in the ground-truth
    // matrix (BFCL: "Unexpected parameter").
    val modelKeysOk = modelMap.keys.forall(matrix.contains)
    if (!modelKeysOk) return false

    // BFCL's exact rule for presence/omission: for each GT param,
    // present → value must match one of `possible_answer[param]`;
    // missing → `""` must be in `possible_answer[param]`.
    matrix.forall { case (name, allowedJson) =>
      val allowed = allowedJson.asVector.toList
      modelMap.get(name) match {
        case Some(mv) => allowed.exists(paramValueMatches(mv, _))
        case None     => allowed.exists(isOmissionSentinel)
      }
    }
  }

  /**
   * Match a model's parameter value against one element of the
   * allowed-values list. Dispatches by model-value type:
   *
   *   - string       → [[stringMatches]]
   *   - number       → numeric equality (int/float interchangeable)
   *   - bool         → exact
   *   - list/array   → [[listMatches]] (may be list-of-strings or list-of-dicts)
   *   - dict/object  → [[dictMatches]]
   */
  private def paramValueMatches(model: Json, allowed: Json): Boolean = {
    if (model == allowed) return true
    (model, allowed) match {
      case (NumDec(a, _), NumDec(b, _)) => a == b
      case (NumInt(a, _), NumInt(b, _)) => a == b
      case (NumDec(a, _), NumInt(b, _)) => a == BigDecimal(b)
      case (NumInt(a, _), NumDec(b, _)) => BigDecimal(a) == b
      case (Str(a, _), Str(b, _))       => standardizeString(a) == standardizeString(b)
      case (Bool(a, _), Bool(b, _))     => a == b
      case (Arr(modelArr, _), Arr(allowedArr, _)) => listMatches(modelArr, allowedArr)
      case (o: Obj, Arr(allowedDictMatrix, _)) =>
        // GT for a dict-valued param looks like `[{k: [values]}, ...]`
        // — i.e. a list of allowed dict shapes. `allowed` here is
        // one element of the outer possible-answer list — so it's
        // itself an Arr of a single allowed-dict, or a list of
        // allowed-dicts representing alternatives. The helper
        // handles both.
        dictMatchesAny(o, Vector(Arr(allowedDictMatrix)))
      case (o: Obj, a: Obj)             => dictMatches(o, a)
      case _                            => false
    }
  }

  /**
   * BFCL list_checker: exact-length match; per-element, string
   * elements go through standardize_string; dict elements recurse
   * via dict_checker; everything else exact-compare.
   */
  private def listMatches(model: Vector[Json], allowed: Vector[Json]): Boolean = {
    if (model.size != allowed.size) return false
    model.zip(allowed).forall { case (m, a) =>
      (m, a) match {
        case (Str(ms, _), Str(as, _)) => standardizeString(ms) == standardizeString(as)
        case (mo: Obj, ao: Obj)       => dictMatches(mo, ao)
        case _                        => paramValueMatches(m, a)
      }
    }
  }

  /**
   * BFCL dict_checker: model's dict against ONE allowed-dict. Each
   * key in model must exist in allowed; each key's value must match
   * one of allowed[key]'s allowed values. Each key in allowed that
   * model omits must have `""` in its allowed list. `allowed` here
   * is a dict whose values are lists of permitted values.
   */
  private def dictMatches(modelDict: Obj, allowedDict: Obj): Boolean = {
    val mv = modelDict.value
    val av = allowedDict.value
    // No extra keys.
    if (!mv.keys.forall(av.contains)) return false
    // Every GT key: either model supplied it with an acceptable
    // value, or it's missing + `""` is in its allowed list.
    av.forall { case (k, allowedForK) =>
      val allowedList = allowedForK match {
        case Arr(a, _) => a
        case single    => Vector(single)
      }
      mv.get(k) match {
        case Some(modelV) => allowedList.exists(paramValueMatches(modelV, _))
        case None         => allowedList.exists(isOmissionSentinel)
      }
    }
  }

  /**
   * For a model dict value compared against a list of alternative
   * allowed-dict shapes. Matches if any alternative accepts it.
   * Skips the `""` sentinel that BFCL uses to indicate "this param
   * is optional at the dict level".
   */
  private def dictMatchesAny(modelDict: Obj, allowedDicts: Vector[Json]): Boolean = {
    allowedDicts.exists {
      case Arr(inner, _)   => inner.exists {
        case o: Obj => dictMatches(modelDict, o)
        case _      => false
      }
      case o: Obj          => dictMatches(modelDict, o)
      case Str("", _)      => false   // sentinel — can't match a present value
      case _               => false
    }
  }

  /**
   * Mirrors BFCL's `standardize_string` normalization. Strips
   * ` , . / - _ * ^`, lowercases, `'` → `"`. So `"x^2"` and
   * `"x**2"` both standardize to `"x2"` and compare equal. BFCL's
   * scorer applies this to every string comparison (param values,
   * list elements, dict values).
   */
  private val punctuationPattern = "[ ,./\\-_*^]".r
  private def standardizeString(s: String): String =
    punctuationPattern.replaceAllIn(s, "").toLowerCase.replace("'", "\"")

  private def isOmissionSentinel(allowed: Json): Boolean = allowed match {
    case Str("", _) => true
    case Null       => true
    case _          => false
  }
}
