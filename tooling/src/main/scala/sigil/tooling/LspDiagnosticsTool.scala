package sigil.tooling

import fabric.rw.*
import org.eclipse.lsp4j.{Diagnostic, DiagnosticSeverity}
import rapid.{Stream, Task}
import sigil.TurnContext
import sigil.event.{Event, Message, MessageRole, MessageVisibility}
import sigil.signal.EventState
import sigil.tool.{ToolExample, ToolInput, ToolName, TypedTool}
import sigil.tool.model.ResponseContent

import java.io.File
import java.nio.file.{Files, Paths}

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
 */
final class LspDiagnosticsTool(manager: LspManager) extends TypedTool[LspDiagnosticsInput](
  name = ToolName("lsp_diagnostics"),
  description =
    """Fetch the language server's diagnostics for a file (errors, warnings, hints).
      |
      |`languageId` selects the persisted LspServerConfig (e.g. "scala", "rust", "python").
      |`filePath` is the absolute path to the file. The session's project root is resolved
      |from the config's `rootMarkers` walked up from the file's directory.
      |`waitMs` (default 1500) is how long to wait for the server to finish publishing
      |diagnostics after opening the file. Pass 0 to read the existing snapshot only.""".stripMargin,
  examples = List(
    ToolExample(
      "scala diagnostics for a single file",
      LspDiagnosticsInput(languageId = "scala", filePath = "/abs/path/to/Foo.scala")
    )
  )
) {
  override protected def executeTyped(input: LspDiagnosticsInput, context: TurnContext): Stream[Event] = {
    val task = Task.defer {
      manager.configFor(input.languageId).flatMap {
        case None =>
          Task.pure(reply(context, s"No LspServerConfig persisted for '${input.languageId}'.", isError = true))
        case Some(config) =>
          val root = manager.resolveRoot(input.filePath, config.rootMarkers)
          val uri = new File(input.filePath).toURI.toString
          manager.session(input.languageId, root).flatMap { session =>
            val text = scala.util.Try(Files.readString(Paths.get(input.filePath))).toOption.getOrElse("")
            for {
              _ <- session.didOpen(uri, input.languageId, text)
              _ <- if (input.waitMs > 0) session.waitForDiagnostics(input.waitMs) else Task.unit
            } yield {
              val diags = session.diagnosticsFor(uri)
              reply(context, renderDiagnostics(input.filePath, diags), isError = false)
            }
          }.handleError { e =>
            Task.pure(reply(context, s"LSP error: ${e.getMessage}", isError = true))
          }
      }
    }
    Stream.force(task.map(Stream.emit))
  }

  private def renderDiagnostics(filePath: String, diags: List[Diagnostic]): String =
    if (diags.isEmpty) s"$filePath: 0 diagnostics."
    else {
      val rendered = diags.map { d =>
        val sev = d.getSeverity match {
          case null                           => "unknown"
          case DiagnosticSeverity.Error       => "error"
          case DiagnosticSeverity.Warning     => "warning"
          case DiagnosticSeverity.Information => "info"
          case DiagnosticSeverity.Hint        => "hint"
        }
        val r = d.getRange
        val pos = s"${r.getStart.getLine + 1}:${r.getStart.getCharacter + 1}"
        s"  [$sev] $pos: ${d.getMessage}"
      }.mkString("\n")
      s"$filePath: ${diags.size} diagnostic(s).\n$rendered"
    }

  private def reply(context: TurnContext, text: String, isError: Boolean): Event =
    Message(
      participantId = context.caller,
      conversationId = context.conversation.id,
      topicId = context.conversation.currentTopicId,
      content = Vector(ResponseContent.Text(text)),
      state = EventState.Complete,
      role = MessageRole.Tool,
      visibility = MessageVisibility.All
    )
}
