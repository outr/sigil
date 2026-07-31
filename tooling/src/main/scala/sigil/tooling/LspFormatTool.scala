package sigil.tooling

import fabric.rw.*
import org.eclipse.lsp4j.FormattingOptions
import rapid.Task
import sigil.tool.ToolContext
import sigil.tool.{DiscoverySpec, Effect, MutationTargeting, Resolution, Tool, ToolIO, ToolInput, ToolName, ToolProfile, ToolSpec}
import sigil.tooling.types.LspFormatResult

import java.nio.file.{Files, Paths, StandardOpenOption}

case class LspFormatInput(languageId: String, filePath: String, tabSize: Int = 2, insertSpaces: Boolean = true) extends ToolInput derives RW

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
final class LspFormatTool(val manager: LspManager) extends Tool with LspToolSupport {
  type Input = LspFormatInput
  type Output = LspFormatResult
  val io: ToolIO[LspFormatInput, LspFormatResult] = ToolIO.derived[LspFormatInput, LspFormatResult]
  override val name = ToolName("lsp_format")
  override val description =
    """Format a file via the language server's formatting provider.
      |
      |`languageId` + `filePath` identify the document.
      |`tabSize` (default 2) and `insertSpaces` (default true) are passed as
      |FormattingOptions to the server; many servers honor only the project's
      |configured formatter and ignore these.
      |Writes the formatted result back to disk; returns `{filePath, editsApplied}`.""".stripMargin
  val spec: ToolSpec = ToolSpec(
    name = name,
    description = description,
    profile = ToolProfile(effect = Effect.Destructive(MutationTargeting.none, "DESTRUCTIVE.")),
    discovery = DiscoverySpec(
      keywords = Set("lsp", "format", "prettify", "indent", "beautify", "reformat", "style"),
      toolchain = Some("lsp")
    )
  )

  protected def resolve: Resolution[Input, Output] = Resolution.Simple(executeOutput)

  private def executeOutput(input: LspFormatInput, context: ToolContext): Task[LspFormatResult] =
    withOpenDocumentOrThrow[LspFormatResult](
      input.languageId,
      input.filePath,
      context
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
