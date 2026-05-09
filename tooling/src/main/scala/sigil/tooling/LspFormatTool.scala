package sigil.tooling

import fabric.rw.*
import org.eclipse.lsp4j.FormattingOptions
import rapid.Task
import sigil.TurnContext
import sigil.tool.{ToolExample, ToolInput, ToolName, TypedOutputTool}
import sigil.tooling.types.LspFormatResult

import java.nio.file.{Files, Paths, StandardOpenOption}

case class LspFormatInput(languageId: String,
                          filePath: String,
                          tabSize: Int = 2,
                          insertSpaces: Boolean = true) extends ToolInput derives RW

/**
 * Format an entire file via the language server's formatting
 * provider. Writes the result back to disk and notifies the server
 * of the change so subsequent diagnostics see the formatted text.
 *
 * The agent uses this after edits to keep style consistent with the
 * project's configured formatter (scalafmt for Scala, rustfmt for
 * Rust, etc.). Servers that don't have formatting support return an
 * empty edit list — the file is unchanged.
 */
final class LspFormatTool(val manager: LspManager) extends TypedOutputTool[LspFormatInput, LspFormatResult](
  name = ToolName("lsp_format"),
  description =
    """Format a file via the language server's formatting provider.
      |
      |`languageId` + `filePath` identify the document.
      |`tabSize` (default 2) and `insertSpaces` (default true) are passed as
      |FormattingOptions to the server; many servers honor only the project's
      |configured formatter and ignore these.
      |Writes the formatted result back to disk; returns `{filePath, editsApplied}`.""".stripMargin,
  keywords = Set("lsp", "format", "prettify", "indent", "beautify", "reformat", "style"),
  examples = List(
    ToolExample(
      "format a Scala file",
      LspFormatInput(languageId = "scala", filePath = "/abs/path/Foo.scala")
    )
  )
) with LspToolSupport {
  override protected def executeTyped(input: LspFormatInput, context: TurnContext): Task[LspFormatResult] =
    withOpenDocumentTyped[LspFormatResult](
      input.languageId, input.filePath, context,
      onError = msg => throw new RuntimeException(msg)
    ) { (session, uri) =>
      val opts = new FormattingOptions(input.tabSize, input.insertSpaces)
      session.formatting(uri, opts).flatMap { edits =>
        if (edits.isEmpty) Task.pure(LspFormatResult(input.filePath, editsApplied = 0))
        else Task {
          val path = Paths.get(input.filePath)
          val contents = Files.readString(path)
          val updated = WorkspaceEditApplier.applyTextEdits(contents, edits)
          Files.writeString(path, updated, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)
          edits.size
        }.flatMap { applied =>
          val text = Files.readString(Paths.get(input.filePath))
          session.didChangeFull(uri, text).map(_ => LspFormatResult(input.filePath, editsApplied = applied))
        }
      }
    }
}
