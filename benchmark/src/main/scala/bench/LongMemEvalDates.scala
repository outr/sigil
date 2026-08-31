package bench

/**
 * LongMemEval's session date stamps. Shared by the retrieval runner
 * ([[LongMemEvalBench]]) and the end-to-end QA runner
 * ([[LongMemEvalQABench]]) — two parsers for one wire format is a
 * drift waiting to happen, and the QA runner's temporal-reasoning
 * category depends on getting it right.
 */
object LongMemEvalDates {

  /**
   * `"2023/05/30 (Tue) 23:40"` → epoch millis. Falls back to "now"
   * for an unparseable stamp, matching the retrieval runner's
   * long-standing behaviour.
   */
  def parse(dateStr: String): Long =
    try {
      val clean = dateStr.replaceAll("\\([A-Za-z]+\\)\\s*", "").trim
      val fmt = java.time.format.DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm")
      java.time.LocalDateTime.parse(clean, fmt)
        .atZone(java.time.ZoneId.of("UTC")).toInstant.toEpochMilli
    } catch {
      case _: Exception => System.currentTimeMillis()
    }

  /**
   * The date as the model should see it inside a memory's text.
   * LongMemEval's temporal-reasoning questions ("four weeks ago",
   * "last Tuesday") are unanswerable unless the retrieved evidence
   * carries when it happened — a memory store that drops the session
   * date makes that whole category chance-level regardless of the
   * model or the retriever.
   */
  def prefix(dateStr: String): String = {
    val clean = dateStr.replaceAll("\\([A-Za-z]+\\)\\s*", "").trim
    if (clean.isEmpty) "" else s"[$clean] "
  }
}
