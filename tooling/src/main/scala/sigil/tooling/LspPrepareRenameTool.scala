package sigil.tooling

import fabric.rw.*
import rapid.Task
import sigil.tool.ToolContext
import sigil.tool.{Tool, ToolInput, ToolName}
import sigil.tooling.types.{LspPrepareRenameResult, LspRange}

case class LspPrepareRenameInput(languageId: String,
                                 filePath: String,
                                 line: Int,
                                 character: Int) extends ToolInput derives RW

/**
 * Test whether a symbol at a position can be renamed before
 * committing to the round-trip. Returns the editable range when yes,
 * a sentinel when no. The agent uses this to fail fast for "rename
 * this thing" attempts on positions that aren't valid symbols
 * (whitespace, keywords, etc.).
 */
final class LspPrepareRenameTool(val manager: LspManager) extends Tool
  with sigil.tool.ReadOnlyExternalTool with LspToolSupport {
  type Input  = LspPrepareRenameInput
  type Output = LspPrepareRenameResult
  val inputRW  = summon[RW[LspPrepareRenameInput]]
  val outputRW = summon[RW[LspPrepareRenameResult]]

  val name = ToolName("lsp_prepare_rename")
  val description =
    """Check whether a symbol at a position is renameable.
      |
      |`languageId` + `filePath` identify the document.
      |`line` + `character` (0-based) point at the candidate symbol.
      |Returns `Renameable(range)` when yes, `NotRenameable` when no.""".stripMargin
  override val keywords = Set("lsp", "rename", "refactor", "can rename", "renameable", "prepare")


  override def executeOutput(input: LspPrepareRenameInput, context: ToolContext): Task[LspPrepareRenameResult] =
    withOpenDocumentOrThrow[LspPrepareRenameResult](
      input.languageId, input.filePath, context
    ) { (session, uri) =>
      session.prepareRename(uri, input.line, input.character).map {
        case None        => LspPrepareRenameResult.NotRenameable
        case Some(range) => LspPrepareRenameResult.Renameable(LspRange.fromLsp4j(range))
      }
    }
}
