package sigil.tooling.types

import fabric.rw.*

/**
 * Tool-emission shape for `lsp_diagnostics` and
 * `lsp_pull_diagnostics`. Carries the file the diagnostics belong
 * to plus the typed diagnostic list — agents pattern-match on
 * `severity == LspSeverity.Error`, iterate `range`, etc. without
 * parsing rendered strings.
 *
 * `fresh` disambiguates an empty `diagnostics` list: `true` means the
 * server published diagnostics for THIS file's current text during
 * the call — empty genuinely means "no issues". `false` means no
 * fresh publish arrived before the wait elapsed (server still
 * indexing, cold build import, `waitMs = 0` snapshot read) — the
 * file's diagnostic state is UNKNOWN and an empty list must NOT be
 * read as clean. Server-level errors still propagate as tool-level
 * error messages via [[sigil.tooling.LspToolSupport.reply]].
 */
case class LspDiagnosticsResult(filePath: String,
                                diagnostics: List[LspDiagnostic],
                                fresh: Boolean = true)
  extends sigil.tool.ToolOutput derives RW {

  /**
   * Line-oriented render — verdict + counts on line 1 (survives any
   * head-truncation), one diagnostic per line after it, errors
   * first. A stale snapshot says so explicitly: an empty stale list
   * must never read as "clean". The typed JSON stays on the output
   * for clients.
   */
  override def modelText: Option[String] = Some {
    val statusLine =
      if (diagnostics.isEmpty) {
        if (fresh) s"no diagnostics — $filePath is clean"
        else s"no diagnostics in the snapshot for $filePath — freshness UNKNOWN (no fresh publish); do NOT treat as clean"
      } else {
        val staleNote = if (fresh) "" else " (STALE snapshot — no fresh publish; the file's current state may differ)"
        s"${DiagnosticLines.countsSummary(diagnostics.map(_.severity))} in $filePath$staleNote"
      }
    (statusLine :: DiagnosticLines.renderLsp(diagnostics)).mkString("\n")
  }
}
