package sigil.tooling

import fabric.rw.*
import rapid.Task
import sigil.TurnContext
import sigil.tool.{ToolExample, ToolInput, ToolName, TypedOutputTool}
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
final class LspPrepareRenameTool(val manager: LspManager) extends TypedOutputTool[LspPrepareRenameInput, LspPrepareRenameResult](
  name = ToolName("lsp_prepare_rename"),
  description =
    """Check whether a symbol at a position is renameable.
      |
      |`languageId` + `filePath` identify the document.
      |`line` + `character` (0-based) point at the candidate symbol.
      |Returns `Renameable(range)` when yes, `NotRenameable` when no.""".stripMargin,
  keywords = Set("lsp", "rename", "refactor", "can rename", "renameable", "prepare"),
  examples = List(
    ToolExample(
      "check before renaming",
      LspPrepareRenameInput(languageId = "scala", filePath = "/abs/path/Foo.scala", line = 10, character = 7)
    )
  )
) with sigil.tool.ReadOnlyExternalTool with LspToolSupport {
  override protected def executeTyped(input: LspPrepareRenameInput, context: TurnContext): Task[LspPrepareRenameResult] =
    withOpenDocumentTyped[LspPrepareRenameResult](
      input.languageId, input.filePath, context,
      onError = msg => throw new RuntimeException(msg)
    ) { (session, uri) =>
      session.prepareRename(uri, input.line, input.character).map {
        case None        => LspPrepareRenameResult.NotRenameable
        case Some(range) => LspPrepareRenameResult.Renameable(LspRange.fromLsp4j(range))
      }
    }
}
