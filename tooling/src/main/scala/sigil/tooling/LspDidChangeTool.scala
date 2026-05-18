package sigil.tooling

import fabric.rw.*
import rapid.Task
import sigil.TurnContext
import sigil.tool.{ToolExample, ToolInput, ToolName, ToolResult, TypedOutputTool}
import sigil.tooling.types.LspDidChangeResult

case class LspDidChangeInput(languageId: String,
                             filePath: String,
                             text: String)
  extends ToolInput derives RW

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
final class LspDidChangeTool(val manager: LspManager)
  extends TypedOutputTool[LspDidChangeInput, LspDidChangeResult](
    name = ToolName("lsp_did_change"),
    description =
      """Update the language server's in-memory copy of a document by passing the file's
      |complete new contents. The server's diagnostic and completion computations operate
      |against this in-memory copy until the next change is sent or the document is
      |closed. Use after any external mutation of the document whose effects the LSP
      |server should see.
      |
      |The `text` argument is the file's COMPLETE new contents, not a query or diff.
      |`languageId` selects the persisted LspServerConfig. `filePath` is the absolute
      |path; the server's open-document state for the URI is refreshed with `text` and
      |the document version is bumped.""".stripMargin,
    keywords = Set("lsp", "did change", "edit", "change", "modify", "document update", "notify edit"),
    examples = List(
      ToolExample(
        "refresh after editing a Scala file",
        LspDidChangeInput(languageId = "scala", filePath = "/abs/path/Foo.scala", text = "object Foo")
      )
    )
  )
  with sigil.tool.DestructiveExternalTool
  with LspToolSupport {
  override def paginate: Boolean = false

  override protected def executeTypedResult(input: LspDidChangeInput,
                                            context: TurnContext): Task[ToolResult[LspDidChangeResult]] =
    // Bug #131 — Sage's wire log showed the agent calling lsp_did_change
    // with 17-character `text` values ("AdminUsersService") trying to
    // QUERY the file. Each call silently overwrote the LSP's in-memory
    // copy with that 17-char string, corrupting the file's state for
    // every subsequent diagnostic / completion call. The tool's name
    // ("did change") didn't make it obvious that this was destructive
    // input-write semantics.
    //
    // Refuse obvious misuse: any `text` below the threshold can't
    // plausibly be a full document; return a structured Failure with
    // a hint pointing at read_file via find_capability.
    if (input.text.length < LspDidChangeTool.MinPlausibleDocumentLength) {
      Task.pure(ToolResult.failure(
        message = s"`text` payload is only ${input.text.length} chars — too short to be a full document.",
        hint = Some(
          "lsp_did_change OVERWRITES the LSP's in-memory copy with this exact string. " +
            "If you wanted to READ the file's contents, use read_file (find via " +
            "`find_capability(\"view file source contents read code\")`). If you wanted to " +
            "send a real edit, pass the file's complete new contents as `text`."
        ),
        args = Some(s"filePath=${input.filePath}, text.length=${input.text.length}")
      ))
    } else {
      withSessionTyped[LspDidChangeResult](
        input.languageId,
        input.filePath,
        context,
        onError = msg => throw new RuntimeException(msg)
      ) { (session, uri, _) =>
        session.didChangeFull(uri, input.text).map(_ => LspDidChangeResult(uri))
      }.map(r => ToolResult.success(r))
        .handleError { err =>
          Task.pure(ToolResult.failure(
            message = Option(err.getMessage).getOrElse(err.getClass.getSimpleName),
            args = Some(s"filePath=${input.filePath}, languageId=${input.languageId}")
          ))
        }
    }
}

object LspDidChangeTool {

  /**
   * Minimum `text` payload length before lsp_did_change accepts it as a
   * plausible full-document update. Below this, the tool refuses with
   * a Failure pointing at read_file — the bug #131 wire-log scenario
   * (agent passing 17-char query strings as `text`) trips here. Apps
   * with legitimately tiny source files (single-line scripts) can
   * override the tool to lower the bar.
   */
  val MinPlausibleDocumentLength: Int = 30
}
