package sigil.tooling.types

import fabric.rw.*

/** Tool-emission shape for `lsp_diagnostics` and
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
  * error messages via [[sigil.tooling.LspToolSupport.reply]]. */
case class LspDiagnosticsResult(filePath: String,
                                diagnostics: List[LspDiagnostic],
                                fresh: Boolean = true) extends sigil.tool.ToolOutput derives RW
