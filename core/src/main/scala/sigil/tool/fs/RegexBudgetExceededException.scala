package sigil.tool.fs

/**
 * Raised when a `grep` regex burns more match steps than its budget
 * allows — the standard ReDoS / catastrophic-backtracking guard for
 * `searchFiles`. #318.
 *
 * The message is the actionable failure text the agent reads: it names
 * the budget that was exceeded, quotes a leading excerpt of the pattern,
 * and points at the usual simplification (collapse `\s+`-separated
 * alternations / nested quantifiers into a single match plus a
 * post-filter, or anchor the alternation).
 */
class RegexBudgetExceededException(val maxSteps: Long, pattern: String)
    extends RuntimeException(RegexBudgetExceededException.message(maxSteps, pattern)) {
  def patternExcerpt: String = RegexBudgetExceededException.excerpt(pattern)
}

object RegexBudgetExceededException {
  /** How many leading characters of the offending pattern to quote. */
  val ExcerptChars: Int = 200

  def excerpt(pattern: String): String =
    if (pattern.length <= ExcerptChars) pattern
    else pattern.take(ExcerptChars) + "…"

  def message(maxSteps: Long, pattern: String): String =
    s"grep aborted: the regex was catastrophically slow and exceeded the $maxSteps-step match budget. " +
      "This usually means many `\\s+`-separated alternations or nested quantifiers that backtrack " +
      "combinatorially. Simplify the pattern — e.g. match a single literal plus a post-filter, or " +
      s"anchor the alternation. Aborted pattern (first $ExcerptChars chars): ${excerpt(pattern)}"
}
