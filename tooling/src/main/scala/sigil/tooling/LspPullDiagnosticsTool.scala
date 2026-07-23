package sigil.tooling

import fabric.rw.*
import rapid.Task
import sigil.tool.ToolContext
import sigil.tool.{Tool, ToolInput, ToolName}
import sigil.tooling.types.{LspDiagnostic, LspDiagnosticsResult}

import scala.jdk.CollectionConverters.*

case class LspPullDiagnosticsInput(languageId: String,
                                   filePath: String) extends ToolInput derives RW

/**
 * Pull-model diagnostics — explicitly request fresh diagnostics for
 * a file rather than waiting on the server to push via
 * `publishDiagnostics`. The LSP 3.17 pull-model is more deterministic
 * for "right now, what's broken in this file" because the server
 * computes and returns synchronously instead of scheduling a publish
 * after some debounce.
 *
 * Falls back to push-model diagnostics if the server doesn't
 * implement pull. Many older servers don't. Agents that need a
 * synchronous answer prefer this; tools waiting on a settled state
 * use [[LspDiagnosticsTool]].
 *
 * Emits a typed [[LspDiagnosticsResult]].
 */
final class LspPullDiagnosticsTool(val manager: LspManager) extends Tool
  with sigil.tool.ReadOnlyExternalTool with LspToolSupport {
  type Input  = LspPullDiagnosticsInput
  type Output = LspDiagnosticsResult
  val inputRW  = summon[RW[LspPullDiagnosticsInput]]
  val outputRW = summon[RW[LspDiagnosticsResult]]

  val name = ToolName("lsp_pull_diagnostics")
  override def verification: Boolean = true
  val description =
    """Pull diagnostics for a file synchronously (LSP 3.17 pull-model).
      |
      |`languageId` + `filePath` identify the document.
      |Returns a verdict line (counts, or an explicit "clean" / "freshness UNKNOWN") followed by one
      |diagnostic per line, errors first. A pull answer is authoritative — an empty result genuinely
      |means the file is clean. Servers without pull-model support fall back to a push-snapshot,
      |which is marked as potentially stale.""".stripMargin
  override val keywords = Set(
    "lsp", "diagnostics", "errors", "warnings", "problems", "lint",
    "analyze", "examine", "inspect", "review", "what's broken",
    "fresh", "sync", "synchronous",
    "scala", "type", "fix", "code", "language"
  )


  override def executeOutput(input: LspPullDiagnosticsInput, context: ToolContext): Task[LspDiagnosticsResult] =
    withOpenDocumentOrThrow[LspDiagnosticsResult](
      input.languageId, input.filePath, context
    ) { (session, uri) =>
      session.pullDiagnosticsVerdict(uri).map {
        case Some(items) =>
          LspDiagnosticsResult(
            filePath    = input.filePath,
            diagnostics = items.map(LspDiagnostic.fromLsp4j(input.filePath, _)),
            fresh       = true
          )
        case None =>
          LspDiagnosticsResult(
            filePath    = input.filePath,
            diagnostics = session.diagnosticsFor(uri).map(LspDiagnostic.fromLsp4j(input.filePath, _)),
            fresh       = false
          )
      }
    }
}
