package sigil.tooling

import fabric.rw.*
import org.eclipse.lsp4j.Diagnostic
import rapid.Task
import sigil.TurnContext
import sigil.tool.{ToolExample, ToolInput, ToolName, TypedOutputTool}
import sigil.tooling.types.{LspDiagnostic, LspDiagnosticsResult}

case class LspDiagnosticsInput(languageId: String,
                               filePath: String,
                               waitMs: Long = 1500L) extends ToolInput derives RW

/**
 * Returns the language server's current diagnostics for a file —
 * type errors, lint warnings, unused imports, etc. The agent's
 * primary "did my edit compile" feedback loop. Opens the file with
 * the server (idempotent), waits a short window for the server to
 * publish diagnostics, then snapshots them.
 *
 * `waitMs` is caller-controlled because different servers settle at
 * different speeds — Metals on cold cache can take 1–2s; rust-analyzer
 * is sub-second after warm-up. Tools that already opened the file
 * earlier in the turn pass `0` to read the latest snapshot directly.
 *
 * Bug #9 phase 6: emits a typed [[LspDiagnosticsResult]] JSON shape
 * — `{filePath, diagnostics: [{filePath, range:{start, end},
 * severity, message, code, source}, ...]}` — so agents can iterate
 * the typed list and pattern-match on severity instead of regex-
 * parsing rendered strings.
 */
final class LspDiagnosticsTool(val manager: LspManager) extends TypedOutputTool[LspDiagnosticsInput, LspDiagnosticsResult](
  name = ToolName("lsp_diagnostics"),
  description =
    """Fetch the language server's diagnostics for a file (errors, warnings, hints).
      |
      |`languageId` selects the persisted LspServerConfig (e.g. "scala", "rust", "python").
      |`filePath` is the absolute path to the file. The session's project root is resolved
      |from the config's `rootMarkers` walked up from the file's directory.
      |`waitMs` (default 1500) is how long to wait for the server to finish publishing
      |diagnostics after opening the file. Pass 0 to read the existing snapshot only.
      |
      |Returns `{filePath, diagnostics: [{range:{start, end}, severity, message, code, source}]}`.""".stripMargin,
  keywords = Set(
    "lsp", "language", "diagnostics", "errors", "warnings", "problems",
    "lint", "compile-check", "analyze", "examine", "inspect", "review",
    "evaluate", "what's broken", "issues", "semantic",
    "scala", "type", "fix", "code"
  ),
  examples = List(
    ToolExample(
      "scala diagnostics for a single file",
      LspDiagnosticsInput(languageId = "scala", filePath = "/abs/path/to/Foo.scala")
    )
  )
) with sigil.tool.ReadOnlyExternalTool with LspToolSupport {
  override protected def executeTyped(input: LspDiagnosticsInput, context: TurnContext): Task[LspDiagnosticsResult] =
    withOpenDocumentTyped[LspDiagnosticsResult](
      input.languageId, input.filePath, context,
      onError = msg => throw new RuntimeException(msg)
    ) { (session, uri) =>
      val wait = if (input.waitMs > 0) session.waitForDiagnostics(input.waitMs) else Task.unit
      wait.map(_ => LspDiagnosticsResult(
        filePath    = input.filePath,
        diagnostics = session.diagnosticsFor(uri).map(LspDiagnostic.fromLsp4j(input.filePath, _))
      ))
    }
}
