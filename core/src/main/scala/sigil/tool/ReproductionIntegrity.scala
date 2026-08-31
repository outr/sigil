package sigil.tool

/**
 * Pure line-survival validator for whole-artifact LLM rewrites.
 *
 * The failure mode this catches is SILENT content loss that still parses:
 * models near their output limit drop lines with `finish_reason = stop` — a
 * vanished `}`, a mid-sentence comment continuation — and syntax checks pass
 * anyway. Line survival catches what parsing cannot.
 *
 * [[validate]] aligns original to refactored via a longest-common-subsequence
 * over lines (identity match, whitespace-exact — a changed indent on a code
 * line IS an alteration). Original lines outside the LCS are candidate
 * violations; the caller's `editable` predicate admits the lines the
 * instruction was allowed to change or remove. Insertions are always allowed.
 *
 * Classification is a best-effort positional pairing within each gap between
 * consecutive LCS matches: the k-th unmatched original line pairs with the
 * k-th inserted refactored line in the same gap and reports as [[AlteredLine]]
 * with `replacedWith = Some(insertion)`; unmatched original lines beyond the
 * gap's insertions report as [[DroppedLine]]. The ok/not-ok verdict does not
 * depend on the pairing — every non-editable original line missing from the
 * LCS is a violation of one kind or the other.
 */
object ReproductionIntegrity {

  /**
   * A non-editable original line that did not survive verbatim, in order.
   * `lineNumber` is the 1-indexed position in the original.
   */
  sealed trait Violation {
    def lineNumber: Int
    def text: String
  }

  /**
   * The original line vanished with no replacement at its position.
   */
  final case class DroppedLine(lineNumber: Int, text: String) extends Violation

  /**
   * The original line was replaced by different content at its position.
   */
  final case class AlteredLine(lineNumber: Int, text: String, replacedWith: Option[String]) extends Violation

  final case class Verdict(violations: List[Violation]) {
    def ok: Boolean = violations.isEmpty
  }

  /**
   * Validate that every original line survives IN ORDER in `refactored`,
   * except lines `editable` admits. `editable(line)` returns true for
   * original lines the instruction was allowed to change or remove (e.g.
   * "comment lines only" for a comment-sweep). Non-editable lines that
   * vanished or changed are violations. Insertions are always allowed.
   *
   * Order matters: LCS keeps in-order matches, so a line MOVED away from
   * its position is a drop at the old position plus an insertion at the
   * new one — it flags rather than silently passing.
   */
  def validate(original: String, refactored: String, editable: String => Boolean): Verdict = {
    val orig = original.linesIterator.toIndexedSeq
    val ref = refactored.linesIterator.toIndexedSeq
    val n = orig.length
    val m = ref.length
    // dp(i)(j) = LCS length of orig(i..n) vs ref(j..m).
    val dp = Array.ofDim[Int](n + 1, m + 1)
    var i = n - 1
    while (i >= 0) {
      var j = m - 1
      while (j >= 0) {
        dp(i)(j) =
          if (orig(i) == ref(j)) dp(i + 1)(j + 1) + 1
          else math.max(dp(i + 1)(j), dp(i)(j + 1))
        j -= 1
      }
      i -= 1
    }

    val violations = List.newBuilder[Violation]
    val gapOrig = scala.collection.mutable.ListBuffer.empty[(Int, String)]
    val gapRef = scala.collection.mutable.ListBuffer.empty[String]

    def flushGap(): Unit = {
      gapOrig.zipWithIndex.foreach { case ((lineNumber, text), idx) =>
        if (!editable(text)) {
          gapRef.lift(idx) match {
            case Some(replacement) => violations += AlteredLine(lineNumber, text, Some(replacement))
            case None => violations += DroppedLine(lineNumber, text)
          }
        }
      }
      gapOrig.clear()
      gapRef.clear()
    }

    var oi = 0
    var rj = 0
    while (oi < n && rj < m)
      if (orig(oi) == ref(rj) && dp(oi)(rj) == dp(oi + 1)(rj + 1) + 1) {
        flushGap()
        oi += 1
        rj += 1
      } else if (dp(oi + 1)(rj) >= dp(oi)(rj + 1)) {
        gapOrig += ((oi + 1, orig(oi)))
        oi += 1
      } else {
        gapRef += ref(rj)
        rj += 1
      }
    while (oi < n) {
      gapOrig += ((oi + 1, orig(oi)))
      oi += 1
    }
    while (rj < m) {
      gapRef += ref(rj)
      rj += 1
    }
    flushGap()
    Verdict(violations.result())
  }
}
