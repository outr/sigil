package sigil.tooling

import fabric.rw.*
import rapid.Stream
import sigil.TurnContext
import sigil.event.Event
import sigil.tool.{ToolExample, ToolInput, ToolName, TypedTool}

case class LspPrepareRenameInput(languageId: String,
                                 filePath: String,
                                 line: Int,
                                 character: Int) extends ToolInput derives RW

/**
 * Test whether a symbol at a position can be renamed before
 * committing to the round-trip. Returns the editable range when yes,
 * an explanatory message when no. The agent uses this to fail fast
 * for "rename this thing" attempts on positions that aren't valid
 * symbols (whitespace, keywords, etc.).
 */
final class LspPrepareRenameTool(val manager: LspManager) extends TypedTool[LspPrepareRenameInput](
  name = ToolName("lsp_prepare_rename"),
  description =
    """Check whether a symbol at a position is renameable.
      |
      |`languageId` + `filePath` identify the document.
      |`line` + `character` (0-based) point at the candidate symbol.
      |Returns the editable range when yes, "not renameable" when no.""".stripMargin,
  examples = List(
    ToolExample(
      "check before renaming",
      LspPrepareRenameInput(languageId = "scala", filePath = "/abs/path/Foo.scala", line = 10, character = 7)
    )
  )
) with LspToolSupport {
  override protected def executeTyped(input: LspPrepareRenameInput, context: TurnContext): Stream[Event] =
    withOpenDocument(input.languageId, input.filePath, context) { (session, uri) =>
      session.prepareRename(uri, input.line, input.character).map {
        case None => "Not renameable at this position."
        case Some(range) =>
          val s = range.getStart
          val e = range.getEnd
          s"Renameable range: ${s.getLine + 1}:${s.getCharacter + 1} – ${e.getLine + 1}:${e.getCharacter + 1}"
      }
    }
}
