package sigil.tooling

import fabric.rw.*
import org.eclipse.lsp4j.DocumentDiagnosticReport
import rapid.Task
import sigil.TurnContext
import sigil.tool.{ToolExample, ToolInput, ToolName, TypedOutputTool}
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
final class LspPullDiagnosticsTool(val manager: LspManager) extends TypedOutputTool[LspPullDiagnosticsInput, LspDiagnosticsResult](
  name = ToolName("lsp_pull_diagnostics"),
  description =
    """Pull diagnostics for a file synchronously (LSP 3.17 pull-model).
      |
      |`languageId` + `filePath` identify the document.
      |Returns `{filePath, diagnostics: [...]}`. Servers without pull-model support fall back to
      |a push-snapshot.""".stripMargin,
  keywords = Set(
    "lsp", "diagnostics", "errors", "warnings", "problems", "lint",
    "analyze", "examine", "inspect", "review", "what's broken",
    "fresh", "sync", "synchronous",
    "scala", "type", "fix", "code", "language"
  ),
  examples = List(
    ToolExample(
      "pull diagnostics for a single file",
      LspPullDiagnosticsInput(languageId = "scala", filePath = "/abs/path/Foo.scala")
    )
  )
) with sigil.tool.ReadOnlyExternalTool with LspToolSupport {
  override protected def executeTyped(input: LspPullDiagnosticsInput, context: TurnContext): Task[LspDiagnosticsResult] =
    withOpenDocumentTyped[LspDiagnosticsResult](
      input.languageId, input.filePath, context,
      onError = msg => throw new RuntimeException(msg)
    ) { (session, uri) =>
      // Sigil bug #100 — gate the pull request on the server's
      // advertised capability. LSP 3.17 says clients MUST NOT call
      // `textDocument/diagnostic` unless the server's
      // `serverCapabilities.diagnosticProvider` is set during
      // `initialize`. Metals (and many production servers) implement
      // only the legacy push flow via `publishDiagnostics`; calling
      // pull against them returns `MethodNotFound`. When the server
      // doesn't advertise pull, fall back to the push-cache
      // [[LspSession.diagnosticsFor]] populated by the recording
      // client's notification handler — agents get a synchronous
      // answer either way.
      val serverSupportsPull = Option(session.serverCapabilities.getDiagnosticProvider).isDefined
      val task: Task[List[LspDiagnostic]] =
        if (serverSupportsPull) {
          session.pullDiagnostics(uri).map { report =>
            val items = report match {
              case Some(r) if r.isLeft => Option(r.getLeft.getItems).map(_.asScala.toList).getOrElse(Nil)
              case _                   => Nil
            }
            items.map(LspDiagnostic.fromLsp4j(input.filePath, _))
          }
        } else {
          Task(session.diagnosticsFor(uri).map(LspDiagnostic.fromLsp4j(input.filePath, _)))
        }
      task.map(diags => LspDiagnosticsResult(filePath = input.filePath, diagnostics = diags))
    }
}
