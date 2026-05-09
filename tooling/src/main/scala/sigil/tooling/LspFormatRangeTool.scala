package sigil.tooling

import fabric.rw.*
import org.eclipse.lsp4j.{FormattingOptions, Position, Range}
import rapid.Task
import sigil.TurnContext
import sigil.tool.{ToolExample, ToolInput, ToolName, TypedOutputTool}
import sigil.tooling.types.LspFormatResult

import java.nio.file.{Files, Paths, StandardOpenOption}

case class LspFormatRangeInput(languageId: String,
                               filePath: String,
                               startLine: Int,
                               startCharacter: Int,
                               endLine: Int,
                               endCharacter: Int,
                               tabSize: Int = 2,
                               insertSpaces: Boolean = true) extends ToolInput derives RW

/**
 * Format a specific range within a file. Useful when the agent has
 * just edited a method body and wants only that block re-flowed —
 * avoids touching unrelated style choices elsewhere in the file.
 *
 * Same writeback semantics as [[LspFormatTool]]: applies the edits
 * to disk and notifies the server.
 */
final class LspFormatRangeTool(val manager: LspManager) extends TypedOutputTool[LspFormatRangeInput, LspFormatResult](
  name = ToolName("lsp_format_range"),
  description =
    """Format a specific range within a file via the language server.
      |
      |`languageId` + `filePath` identify the document.
      |`startLine`/`startCharacter`/`endLine`/`endCharacter` (0-based) define the range.
      |`tabSize` and `insertSpaces` are passed as FormattingOptions.
      |Writes the formatted result back to disk; returns `{filePath, editsApplied}`.""".stripMargin,
  keywords = Set("lsp", "format", "format range", "prettify", "indent", "beautify", "selection"),
  examples = List(
    ToolExample(
      "format a single method body",
      LspFormatRangeInput(
        languageId = "scala", filePath = "/abs/path/Foo.scala",
        startLine = 10, startCharacter = 0,
        endLine = 25, endCharacter = 0
      )
    )
  )
) with LspToolSupport {
  override protected def executeTyped(input: LspFormatRangeInput, context: TurnContext): Task[LspFormatResult] =
    withOpenDocumentTyped[LspFormatResult](
      input.languageId, input.filePath, context,
      onError = msg => throw new RuntimeException(msg)
    ) { (session, uri) =>
      val range = new Range(
        new Position(input.startLine, input.startCharacter),
        new Position(input.endLine, input.endCharacter)
      )
      val opts = new FormattingOptions(input.tabSize, input.insertSpaces)
      session.rangeFormatting(uri, range, opts).flatMap { edits =>
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
