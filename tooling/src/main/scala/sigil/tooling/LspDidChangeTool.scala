package sigil.tooling

import fabric.rw.*
import rapid.Stream
import sigil.TurnContext
import sigil.event.Event
import sigil.tool.{ToolExample, ToolInput, ToolName, TypedTool}

case class LspDidChangeInput(languageId: String,
                             filePath: String,
                             text: String) extends ToolInput derives RW

/**
 * Send a full-document content update to the language server.
 *
 * Use after rewriting a file (e.g. via `EditFileTool` /
 * `WriteFileTool`) so the LSP server's in-memory copy matches the
 * disk and subsequent `lsp_diagnostics`, `lsp_completion`, etc.
 * see the new text. Apps that wire `LspManager.notifyFileChanged`
 * from their write tools may not need this directly — the
 * `workspace/didChangeWatchedFiles` notification is the typical
 * fan-out path. This tool exists for explicit "refresh now" flows.
 */
final class LspDidChangeTool(val manager: LspManager) extends TypedTool[LspDidChangeInput](
  name = ToolName("lsp_did_change"),
  description =
    """Notify the language server that a document has changed and pass the new full text.
      |
      |`languageId` selects the persisted LspServerConfig.
      |`filePath` is the absolute path; the server's open-document state for the URI is
      |refreshed with `text` and the document version is bumped.""".stripMargin,
  examples = List(
    ToolExample(
      "refresh after editing a Scala file",
      LspDidChangeInput(languageId = "scala", filePath = "/abs/path/Foo.scala", text = "object Foo")
    )
  )
) with LspToolSupport {
  override protected def executeTyped(input: LspDidChangeInput, context: TurnContext): Stream[Event] =
    withSession(input.languageId, input.filePath, context) { (session, uri, _) =>
      session.didChangeFull(uri, input.text).map(_ => s"Notified server of change to $uri.")
    }
}
