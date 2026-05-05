package sigil.tooling.types

import fabric.rw.*

/** Sigil-flavored mirror of LSP4J's `DiagnosticSeverity` — Error,
  * Warning, Information, Hint. `Unknown` covers the null case
  * LSP4J's enum allows for missing severity. Apps pattern-match on
  * this directly instead of regex-parsing rendered strings. */
enum LspSeverity derives RW {
  case Error, Warning, Information, Hint, Unknown
}

object LspSeverity {
  def fromLsp4j(s: org.eclipse.lsp4j.DiagnosticSeverity): LspSeverity = s match {
    case null                                            => LspSeverity.Unknown
    case org.eclipse.lsp4j.DiagnosticSeverity.Error      => LspSeverity.Error
    case org.eclipse.lsp4j.DiagnosticSeverity.Warning    => LspSeverity.Warning
    case org.eclipse.lsp4j.DiagnosticSeverity.Information => LspSeverity.Information
    case org.eclipse.lsp4j.DiagnosticSeverity.Hint       => LspSeverity.Hint
  }
}
