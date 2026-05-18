package sigil.tooling.types

import fabric.rw.*

/**
 * Tool-emission shape for `lsp_diagnostics` and
 * `lsp_pull_diagnostics`. Carries the file the diagnostics belong
 * to plus the typed diagnostic list — agents pattern-match on
 * `severity == LspSeverity.Error`, iterate `range`, etc. without
 * parsing rendered strings.
 *
 * Empty `diagnostics` means "the server reports no issues" —
 * distinct from a server error (those propagate as a tool-level
 * error message via [[sigil.tooling.LspToolSupport.reply]]).
 */
case class LspDiagnosticsResult(filePath: String,
                                diagnostics: List[LspDiagnostic])
  derives RW
