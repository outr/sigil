package sigil.provider

/**
 * Detects token-level repetition loops in model output — the
 * failure mode where the model emits the same sentence over and
 * over until `max_tokens` fires. Used by the orchestrator's `Done`
 * handler when a stream settles with [[StopReason.MaxTokens]] so
 * the framework can surface the loop as a Failure-block diagnostic
 * instead of silently accepting hundreds of kilobytes of redundant
 * text.
 *
 * The heuristic is deliberately conservative:
 *
 *   - Short outputs are never flagged (`< minLength` chars). Brief
 *     replies with repeated stock phrasing aren't degenerate.
 *   - A response must split into at least `minSentences` sentences
 *     before the share calculation runs (avoids tripping on a
 *     one-paragraph blob).
 *   - The top sentence must account for more than `shareThreshold`
 *     of all sentences AND appear at least `minRepetitions` times.
 *     Together these guard against false positives on legitimate
 *     long-form output that happens to repeat a short connector
 *     phrase.
 *
 * Apps that want different sensitivity tune the constructor params
 * (e.g. a system-prompt-heavy app might lower `shareThreshold` to
 * 0.3 once it has wire-log evidence of the failure shape).
 */
final case class DegenerateContentDetector(minLength: Int = 5_000,
                                           minSentences: Int = 5,
                                           shareThreshold: Double = 0.4,
                                           minRepetitions: Int = 5) {

  /**
   * Inspect `text` and return a [[DegenerateContentDetector.Hit]]
   * describing the dominant repeated sentence when a loop is
   * detected; `None` when the heuristic doesn't trip.
   */
  def detect(text: String): Option[DegenerateContentDetector.Hit] =
    if (text.length < minLength) None
    else {
      val sentences = splitSentences(text)
      if (sentences.length < minSentences) None
      else {
        val counts = sentences.groupBy(normalize).view.mapValues(_.size).toMap
        if (counts.isEmpty) None
        else {
          val (topNormalized, topCount) = counts.maxBy(_._2)
          val share = topCount.toDouble / sentences.length.toDouble
          if (topCount >= minRepetitions && share > shareThreshold) {
            // Surface the first occurrence's original (un-normalized)
            // text so the caller can render the offender verbatim.
            val firstOccurrence = sentences.find(s => normalize(s) == topNormalized).getOrElse(topNormalized)
            Some(DegenerateContentDetector.Hit(
              repeatedSentence = firstOccurrence,
              occurrences = topCount,
              totalSentences = sentences.length,
              share = share
            ))
          } else None
        }
      }
    }

  private def splitSentences(text: String): Array[String] =
    text.split("(?<=[.!?])\\s+").map(_.trim).filter(_.nonEmpty)

  private def normalize(s: String): String =
    s.toLowerCase.replaceAll("\\s+", " ").trim
}

object DegenerateContentDetector {

  /**
   * Match the live `qwen3.6-35b` failure shape from the wire log
   * directly — a typical hit there is sentence-level repetition
   * with a 17/17 share. The defaults are tuned conservatively
   * around that observation.
   */
  val Default: DegenerateContentDetector = DegenerateContentDetector()

  /**
   * What the detector hands back when degenerate output is
   * identified.
   *
   *   - `repeatedSentence` — the offender's first occurrence
   *     (verbatim, before normalisation).
   *   - `occurrences` — count of the repeated sentence.
   *   - `totalSentences` — total sentences in the response.
   *   - `share` — `occurrences / totalSentences`.
   */
  final case class Hit(repeatedSentence: String,
                       occurrences: Int,
                       totalSentences: Int,
                       share: Double) {

    /**
     * Render a diagnostic message the orchestrator surfaces to the
     * agent as a Failure-block `ResponseContent` so the next
     * iteration sees concrete feedback about what went wrong.
     */
    def renderDiagnostic(textLength: Int): String = {
      val sharePct = math.round(share * 100).toInt
      val snippet = if (repeatedSentence.length <= 200) repeatedSentence else repeatedSentence.take(200) + "…"
      s"Model entered a token-level repetition loop ($occurrences/$totalSentences sentences = $sharePct% were the same) before hitting max_tokens. " +
        s"$textLength chars of redundant output. " +
        s"Repeated sentence: \"$snippet\".\n\n" +
        "Your prior approach is stuck. Either call `find_capability` for a different shape of action, " +
        "invoke a concrete tool that advances the task, or set `endsTurn = true` on a respond asking " +
        "the user to narrow the scope. Do NOT retry the same content."
    }
  }
}
