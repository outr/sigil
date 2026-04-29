package sigil.workflow.trigger

/**
 * Pure cron-expression evaluator. Five-field syntax
 * (`minute hour day-of-month month day-of-week`) with the standard
 * shapes per field:
 *
 *   - `*` — every value in `[min, max]`
 *   - `*\/S` — every value stepping by `S`
 *   - `A-B` — inclusive range `[A, B]`
 *   - `A-B/S` — inclusive range stepping by `S`
 *   - `K` — the literal value `K`
 *   - any of the above joined by `,`
 *
 * Per-field bounds:
 *
 *   - minute: 0-59
 *   - hour: 0-23
 *   - day-of-month: 1-31
 *   - month: 1-12
 *   - day-of-week: 0-6 (Sunday = 0)
 *
 * Returns false on parse failure (malformed expression) — defensive
 * against silently firing on misconfigured workflows. Apps can call
 * [[matches]] directly to check whether an arbitrary timestamp would
 * fire under a given expression (useful for previewing schedules).
 */
object CronExpression {

  /** True iff the wall clock at `epochMillis` matches every field of
    * `expression`. Unlike `TimeTriggerImpl.check`, this does NOT
    * suppress duplicate fires within the same minute — that's a
    * concern of the trigger's run loop, not the expression itself. */
  def matches(expression: String, epochMillis: Long): Boolean = {
    val parts = expression.trim.split("\\s+")
    if (parts.length != 5) return false
    val cal = java.util.Calendar.getInstance()
    cal.setTimeInMillis(epochMillis)
    val minute = cal.get(java.util.Calendar.MINUTE)
    val hour   = cal.get(java.util.Calendar.HOUR_OF_DAY)
    val dom    = cal.get(java.util.Calendar.DAY_OF_MONTH)
    val month  = cal.get(java.util.Calendar.MONTH) + 1
    val dow    = {
      val raw = cal.get(java.util.Calendar.DAY_OF_WEEK)
      if (raw == java.util.Calendar.SUNDAY) 0 else raw - 1
    }
    matchField(parts(0), minute, 0, 59) &&
      matchField(parts(1), hour, 0, 23) &&
      matchField(parts(2), dom, 1, 31) &&
      matchField(parts(3), month, 1, 12) &&
      matchField(parts(4), dow, 0, 6)
  }

  private def matchField(field: String, value: Int, min: Int, max: Int): Boolean =
    field.split(",").iterator.map(_.trim).exists(term => matchTerm(term, value, min, max))

  private def matchTerm(term: String, value: Int, min: Int, max: Int): Boolean = {
    parseTerm(term, min, max) match {
      case ParsedTerm.Literal(k)              => k == value
      case ParsedTerm.Range(lo, hi, step)     => value >= lo && value <= hi && ((value - lo) % step == 0)
      case ParsedTerm.Invalid                 => false
    }
  }

  private enum ParsedTerm {
    case Literal(value: Int)
    case Range(lo: Int, hi: Int, step: Int)
    case Invalid
  }

  private def parseTerm(term: String, min: Int, max: Int): ParsedTerm = {
    val splits = term.split('/')
    if (splits.length > 2) return ParsedTerm.Invalid
    val rangeSpec = splits(0)
    val step = if (splits.length == 1) 1 else splits(1).toIntOption.filter(_ > 0).getOrElse(-1)
    if (step <= 0) return ParsedTerm.Invalid

    rangeSpec match {
      case "" => ParsedTerm.Invalid
      case "*" => ParsedTerm.Range(min, max, step)
      case s if s.contains('-') =>
        s.split('-') match {
          case Array(a, b) =>
            (a.toIntOption, b.toIntOption) match {
              case (Some(lo), Some(hi)) if lo <= hi && lo >= min && hi <= max =>
                ParsedTerm.Range(lo, hi, step)
              case _ => ParsedTerm.Invalid
            }
          case _ => ParsedTerm.Invalid
        }
      case s =>
        s.toIntOption match {
          // Step modifier on a literal isn't meaningful — treat as
          // pure literal when step==1, otherwise reject.
          case Some(k) if step == 1 && k >= min && k <= max => ParsedTerm.Literal(k)
          case _                                            => ParsedTerm.Invalid
        }
    }
  }
}
