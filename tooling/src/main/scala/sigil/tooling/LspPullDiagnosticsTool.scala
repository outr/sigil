package sigil.tooling

import fabric.rw.*
import org.eclipse.lsp4j.{DiagnosticSeverity, DocumentDiagnosticReport}
import rapid.Stream
import sigil.TurnContext
import sigil.event.Event
import sigil.tool.{ToolExample, ToolInput, ToolName, TypedTool}

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
 */
final class LspPullDiagnosticsTool(val manager: LspManager) extends TypedTool[LspPullDiagnosticsInput](
  name = ToolName("lsp_pull_diagnostics"),
  description =
    """Pull diagnostics for a file synchronously (LSP 3.17 pull-model).
      |
      |`languageId` + `filePath` identify the document.
      |Returns the server's current diagnostic snapshot. Servers without pull-model
      |support fall back to a push-snapshot.""".stripMargin,
  examples = List(
    ToolExample(
      "pull diagnostics for a single file",
      LspPullDiagnosticsInput(languageId = "scala", filePath = "/abs/path/Foo.scala")
    )
  )
) with LspToolSupport {
  override protected def executeTyped(input: LspPullDiagnosticsInput, context: TurnContext): Stream[Event] =
    withOpenDocument(input.languageId, input.filePath, context) { (session, uri) =>
      session.pullDiagnostics(uri).map(render(input.filePath, _))
    }

  private def render(filePath: String, report: Option[DocumentDiagnosticReport]): String = report match {
    case None => s"$filePath: no pull-model report (server may not support pull diagnostics)."
    case Some(r) =>
      val items = if (r.isLeft) Option(r.getLeft.getItems).map(_.asScala.toList).getOrElse(Nil)
                  else Nil
      if (items.isEmpty) s"$filePath: 0 diagnostics."
      else {
        val rendered = items.map { d =>
          val sev = d.getSeverity match {
            case null                           => "unknown"
            case DiagnosticSeverity.Error       => "error"
            case DiagnosticSeverity.Warning     => "warning"
            case DiagnosticSeverity.Information => "info"
            case DiagnosticSeverity.Hint        => "hint"
          }
          val pos = s"${d.getRange.getStart.getLine + 1}:${d.getRange.getStart.getCharacter + 1}"
          s"  [$sev] $pos: ${d.getMessage}"
        }.mkString("\n")
        s"$filePath: ${items.size} diagnostic(s).\n$rendered"
      }
  }
}
