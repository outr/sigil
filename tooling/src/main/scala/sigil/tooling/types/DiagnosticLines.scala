package sigil.tooling.types

/**
 * Line-oriented rendering for diagnostic lists. A tool result must
 * stay useful under head-truncation: the externalization layer keeps
 * the first bytes of the rendered text, so a single-line JSON blob
 * dies mid-first-diagnostic on exactly the runs with the most errors.
 * Rendering one diagnostic per line — errors before warnings, grouped
 * by file, message continuation lines indented — means any prefix of
 * the output is a valid, actionable error list.
 */
object DiagnosticLines {

  private def rank(s: LspSeverity): Int = s match {
    case LspSeverity.Error => 0
    case LspSeverity.Warning => 1
    case LspSeverity.Information => 2
    case LspSeverity.Hint => 3
    case LspSeverity.Unknown => 4
  }

  private def label(s: LspSeverity): String = s match {
    case LspSeverity.Error => "error"
    case LspSeverity.Warning => "warning"
    case LspSeverity.Information => "info"
    case LspSeverity.Hint => "hint"
    case LspSeverity.Unknown => "diagnostic"
  }

  /**
   * `"N error(s), M warning(s)"` — only severities that occur render.
   */
  def countsSummary(severities: List[LspSeverity]): String = {
    val byRank = severities.groupBy(identity).toList.sortBy { case (s, _) => rank(s) }
    byRank.map { case (s, hits) => s"${hits.size} ${label(s)}(s)" }.mkString(", ")
  }

  /**
   * One diagnostic: primary line `path:line:col: severity: message`,
   * any further message lines indented two spaces so the NEXT
   * diagnostic's primary line is always at column zero.
   */
  def render(filePath: String,
             range: LspRange,
             severity: LspSeverity,
             message: String,
             code: Option[String]): String = {
    val codeSuffix = code.map(c => s" [$c]").getOrElse("")
    val msgLines = message.split('\n')
    val first =
      s"$filePath:${range.start.line}:${range.start.column}: ${label(severity)}: " +
        s"${msgLines.headOption.getOrElse("")}$codeSuffix"
    (first +: msgLines.drop(1).map("  " + _).toSeq).mkString("\n")
  }

  /**
   * Errors before warnings, grouped by file, in line order within a file.
   */
  def renderBsp(diagnostics: List[BspDiagnostic]): List[String] =
    diagnostics
      .sortBy(d => (rank(d.severity), d.filePath, d.range.start.line, d.range.start.column))
      .map(d => render(d.filePath, d.range, d.severity, d.message, d.code))

  /**
   * Errors before warnings, in line order (single-file lists keep
   * their file grouping trivially).
   */
  def renderLsp(diagnostics: List[LspDiagnostic]): List[String] =
    diagnostics
      .sortBy(d => (rank(d.severity), d.filePath, d.range.start.line, d.range.start.column))
      .map(d => render(d.filePath, d.range, d.severity, d.message, d.code))
}
