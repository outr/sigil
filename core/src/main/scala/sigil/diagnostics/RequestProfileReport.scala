package sigil.diagnostics

/**
 * Markdown report builder for a sequence of [[RequestProfile]]s — the
 * Phase 0 instrumentation output that drives the curator's shed-policy
 * design.
 *
 * Given a turn-by-turn sequence of profiles (typically captured from
 * [[sigil.signal.WireRequestProfile]] notices over a benchmark scenario),
 * emits a self-contained markdown summary:
 *   - Overall: total tokens p50 / p95 / max, growth per turn.
 *   - Per-section: average contribution, % of total, max-turn observed.
 *   - Frame breakdown: per-kind (Text / ToolCall / ToolResult / System /
 *     Reasoning) average contribution.
 */
object RequestProfileReport {

  case class SectionStat(section: ProfileSection, avg: Int, p95: Int, max: Int, sharePct: Double)

  /** Emit a markdown report. Header carries the scenario title; the body
    * has overall + per-section + per-frame-kind tables. */
  def render(title: String, profiles: Seq[RequestProfile]): String = {
    if (profiles.isEmpty) return s"# $title\n\nNo profiles captured.\n"

    val totals = profiles.map(_.total).sorted
    val grandTotal = totals.sum.toDouble.max(1.0)

    val sectionStats: List[SectionStat] = ProfileSection.values.toList.flatMap { section =>
      val values = profiles.map(_.sections.getOrElse(section, 0)).filter(_ > 0)
      if (values.isEmpty) None
      else {
        val sorted = values.sorted
        val avg = sorted.sum / sorted.size
        val p95 = percentile(sorted, 0.95)
        val mx = sorted.last
        val share = sorted.sum.toDouble / grandTotal * 100.0
        Some(SectionStat(section, avg, p95, mx, share))
      }
    }.sortBy(-_.sharePct)

    val frameStats: Map[String, (Int, Int, Int)] = {
      val grouped = profiles.flatMap(_.frames).groupBy(_.kind)
      grouped.view.mapValues { fs =>
        val tokens = fs.map(_.tokens).sorted
        (tokens.sum / fs.size.max(1), percentile(tokens, 0.95), tokens.lastOption.getOrElse(0))
      }.toMap
    }

    val sb = new StringBuilder
    sb.append(s"# $title\n\n")
    sb.append(s"_${profiles.size} request(s) profiled._\n\n")

    sb.append("## Overall token totals\n\n")
    sb.append("| Metric | Tokens |\n|---|---|\n")
    sb.append(s"| min  | ${totals.head} |\n")
    sb.append(s"| p50  | ${percentile(totals, 0.50)} |\n")
    sb.append(s"| p95  | ${percentile(totals, 0.95)} |\n")
    sb.append(s"| max  | ${totals.last} |\n")
    sb.append(s"| mean | ${totals.sum / totals.size} |\n\n")

    if (profiles.size >= 2) {
      val deltas = profiles.sliding(2).collect { case Seq(a, b) => b.total - a.total }.toList
      if (deltas.nonEmpty) {
        sb.append("## Growth per turn\n\n")
        sb.append("| Metric | Tokens |\n|---|---|\n")
        sb.append(s"| min Δ  | ${deltas.min} |\n")
        sb.append(s"| max Δ  | ${deltas.max} |\n")
        sb.append(s"| mean Δ | ${deltas.sum / deltas.size} |\n\n")
      }
    }

    sb.append("## Per-section contribution\n\n")
    sb.append("| Section | Avg | p95 | Max | Share % |\n|---|---|---|---|---|\n")
    sectionStats.foreach { s =>
      sb.append(f"| ${s.section} | ${s.avg} | ${s.p95} | ${s.max} | ${s.sharePct}%.1f |\n")
    }
    sb.append("\n")

    if (frameStats.nonEmpty) {
      sb.append("## Per-frame-kind contribution\n\n")
      sb.append("| Kind | Avg tokens | p95 | Max |\n|---|---|---|---|\n")
      frameStats.toList.sortBy(-_._2._3).foreach { case (kind, (avg, p95, mx)) =>
        sb.append(s"| $kind | $avg | $p95 | $mx |\n")
      }
      sb.append("\n")
    }

    sb.toString
  }

  /** Write the report to a markdown file under `benchmark/profiles/`. */
  def writeTo(path: java.nio.file.Path, title: String, profiles: Seq[RequestProfile]): Unit = {
    val content = render(title, profiles)
    java.nio.file.Files.createDirectories(path.getParent)
    java.nio.file.Files.writeString(path, content, java.nio.charset.StandardCharsets.UTF_8)
  }

  private def percentile(sortedAsc: Seq[Int], p: Double): Int = {
    if (sortedAsc.isEmpty) 0
    else {
      val idx = math.min((p * sortedAsc.size).toInt, sortedAsc.size - 1)
      sortedAsc(idx)
    }
  }
}
