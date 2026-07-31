package sigil.tooling

import fabric.rw.*
import rapid.Task
import sigil.tool.ToolContext
import sigil.tool.{DiscoverySpec, Effect, MutationTargeting, Tool, ToolInput, ToolName, ToolProfile, ToolResult, ToolSpec}
import sigil.tooling.types.LspDidChangeResult

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
final class LspDidChangeTool(val manager: LspManager) extends Tool
  with LspToolSupport {
  type Input  = LspDidChangeInput
  type Output = LspDidChangeResult
  val inputRW  = summon[RW[LspDidChangeInput]]
  val outputRW = summon[RW[LspDidChangeResult]]
  override val name = ToolName("lsp_did_change")
  override val description =
    """Update the language server's in-memory copy of a document by passing the file's
      |complete new contents. The server's diagnostic and completion computations operate
      |against this in-memory copy until the next change is sent or the document is
      |closed. Use after any external mutation of the document whose effects the LSP
      |server should see.
      |
      |The `text` argument is the file's COMPLETE new contents, not a query or diff.
      |`languageId` selects the persisted LspServerConfig. `filePath` is the absolute
      |path; the server's open-document state for the URI is refreshed with `text` and
      |the document version is bumped.""".stripMargin
  val spec: ToolSpec = ToolSpec(
    name = name,
    description = description,
    profile = ToolProfile(effect = Effect.Destructive(MutationTargeting.none, "DESTRUCTIVE.")),
    discovery = DiscoverySpec(
      keywords = Set("lsp", "did change", "edit", "change", "modify", "document update", "notify edit"),
      toolchain = Some("lsp")
    )
  )

  override def executeResult(input: LspDidChangeInput,
                             context: ToolContext): Task[ToolResult[LspDidChangeResult]] = {
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
      withSessionOrThrow[LspDidChangeResult](
        input.languageId, input.filePath, context
      ) { (session, uri, _) =>
        session.didChangeFull(uri, input.text).map(_ => LspDidChangeResult(uri))
      }.map(r => ToolResult.success(r))
        .handleError { err =>
          Task.pure(ToolResult.failure(
            message = Option(err.getMessage).getOrElse(err.getClass.getSimpleName),
            args    = Some(s"filePath=${input.filePath}, languageId=${input.languageId}")
          ))
        }
    }
  }
}

object LspDidChangeTool {
  /** Minimum `text` payload length before lsp_did_change accepts it as a
    * plausible full-document update. Below this, the tool refuses with
    * a Failure pointing at read_file. Apps with legitimately tiny source
    * files (single-line scripts) can override the tool to lower the bar. */
  val MinPlausibleDocumentLength: Int = 30
}
