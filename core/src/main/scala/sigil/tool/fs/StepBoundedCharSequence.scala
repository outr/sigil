package sigil.tool.fs

/**
 * A [[CharSequence]] view that counts `charAt` accesses and throws a
 * [[RegexBudgetExceededException]] once a step budget is exceeded.
 *
 * `java.util.regex` is a backtracking NFA: catastrophic backtracking
 * shows up as an explosion of `charAt` reads against the input. Wrapping
 * each grep line in this sequence makes `Matcher.find()` interruptible —
 * it can't run unbounded on a pathological model-authored pattern. #318.
 *
 * The budget is shared across every `charAt` of the wrapped line
 * (including the views handed back from `subSequence`), so the count
 * reflects total match effort against that line rather than per-call work.
 */
final class StepBoundedCharSequence private (inner: CharSequence,
                                             maxSteps: Long,
                                             pattern: String,
                                             counter: StepBoundedCharSequence.Counter) extends CharSequence {
  def this(inner: CharSequence, maxSteps: Long, pattern: String) =
    this(inner, maxSteps, pattern, new StepBoundedCharSequence.Counter)

  override def charAt(index: Int): Char = {
    counter.steps += 1
    if (counter.steps > maxSteps) throw new RegexBudgetExceededException(maxSteps, pattern)
    inner.charAt(index)
  }

  override def length: Int = inner.length

  override def subSequence(start: Int, end: Int): CharSequence =
    new StepBoundedCharSequence(inner.subSequence(start, end), maxSteps, pattern, counter)

  override def toString: String = inner.toString
}

object StepBoundedCharSequence {
  /** Mutable shared step counter threaded through every `subSequence`
    * view of one wrapped line. */
  private final class Counter {
    var steps: Long = 0L
  }
}
