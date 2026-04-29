package sigil.tooling

import fabric.rw.*
import org.eclipse.lsp4j.{CodeAction, Command, Position, Range}
import org.eclipse.lsp4j.jsonrpc.messages.{Either => LspEither}
import rapid.Stream
import sigil.TurnContext
import sigil.event.Event
import sigil.tool.{ToolExample, ToolInput, ToolName, TypedTool}

case class LspCodeActionInput(languageId: String,
                              filePath: String,
                              startLine: Int,
                              startCharacter: Int,
                              endLine: Int,
                              endCharacter: Int,
                              onlyKinds: List[String] = Nil) extends ToolInput derives RW

/**
 * Request available code actions for a range — quick fixes,
 * refactorings, source organizers. The server returns a list; the
 * agent picks one and runs it via `lsp_apply_code_action` (which
 * looks up by index in the session's most-recent cache, no
 * serialization needed across the tool boundary).
 *
 * `onlyKinds` (optional) filters by LSP code-action kind, e.g.
 * `["quickfix"]`, `["refactor.extract"]`, `["source.organizeImports"]`
 * — defined in the spec under "CodeActionKind".
 */
final class LspCodeActionTool(val manager: LspManager) extends TypedTool[LspCodeActionInput](
  name = ToolName("lsp_code_action"),
  description =
    """List code actions (quick fixes / refactors) available for a range.
      |
      |`languageId` + `filePath` identify the document.
      |`startLine`/`startCharacter`/`endLine`/`endCharacter` are 0-based; the range covers
      |the selection or cursor span. For a cursor-only invocation, set start == end.
      |`onlyKinds` (optional) filters by LSP code-action kind ("quickfix", "refactor.extract",
      |"source.organizeImports", etc.).
      |Returns numbered action titles; apply by index with `lsp_apply_code_action`.""".stripMargin,
  examples = List(
    ToolExample(
      "scala quick-fixes for a single line",
      LspCodeActionInput(
        languageId = "scala",
        filePath = "/abs/path/Foo.scala",
        startLine = 10, startCharacter = 0,
        endLine = 10, endCharacter = 0,
        onlyKinds = List("quickfix")
      )
    )
  )
) with LspToolSupport {
  override protected def executeTyped(input: LspCodeActionInput, context: TurnContext): Stream[Event] =
    withOpenDocument(input.languageId, input.filePath, context) { (session, uri) =>
      val range = new Range(
        new Position(input.startLine, input.startCharacter),
        new Position(input.endLine, input.endCharacter)
      )
      session.codeAction(uri, range, input.onlyKinds).map { actions =>
        if (actions.isEmpty) "No code actions available."
        else {
          actions.zipWithIndex.map { case (a, idx) => render(a, idx) }.mkString("\n")
        }
      }
    }

  private def render(action: LspEither[Command, CodeAction], idx: Int): String = {
    val (title, kind) =
      if (action.isLeft) (action.getLeft.getTitle, "command")
      else {
        val ca = action.getRight
        val k = Option(ca.getKind).getOrElse("(unknown)")
        (ca.getTitle, k)
      }
    s"  [$idx] [$kind] $title"
  }
}
