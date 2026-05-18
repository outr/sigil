package sigil.tooling

import fabric.rw.*
import org.eclipse.lsp4j.{Position, SelectionRange}
import rapid.Task
import sigil.TurnContext
import sigil.tool.{ToolExample, ToolInput, ToolName, TypedOutputTool}
import sigil.tooling.types.{LspRange, LspSelectionRangeChain, LspSelectionRangeResult}

case class LspSelectionRangeInput(languageId: String,
                                  filePath: String,
                                  positions: List[LspSelectionRangeInput.Pos])
  extends ToolInput derives RW

object LspSelectionRangeInput {
  case class Pos(line: Int, character: Int) derives RW
}

/**
 * Smart selection — for each input position, return the chain of
 * progressively-larger semantic regions enclosing it (identifier
 * → expression → statement → block → method → class …). The agent
 * uses this to widen a selection by syntactic boundary instead of
 * by character count.
 *
 * Equivalent to an editor's "expand selection to enclosing scope"
 * shortcut. Less commonly needed than completion / hover, but
 * essential when the agent is reasoning about "the entire surrounding
 * context" for an edit.
 */
final class LspSelectionRangeTool(val manager: LspManager)
  extends TypedOutputTool[LspSelectionRangeInput, LspSelectionRangeResult](
    name = ToolName("lsp_selection_range"),
    description =
      """For each input cursor position, return the chain of progressively-larger semantic
      |regions enclosing it (identifier → expression → statement → method → class …).
      |
      |`languageId` + `filePath` identify the document.
      |`positions` is the list of (line, character) pairs (0-based).
      |Returns `{filePath, chains: [{ranges: [innermost, ..., outermost]}]}` — one chain per input position.""".stripMargin,
    keywords = Set("lsp", "selection", "expand selection", "smart selection"),
    examples = List(
      ToolExample(
        "expand selection at one position",
        LspSelectionRangeInput(
          languageId = "scala",
          filePath = "/abs/path/Foo.scala",
          positions = List(LspSelectionRangeInput.Pos(line = 10, character = 7))
        )
      )
    )
  )
  with sigil.tool.ReadOnlyExternalTool
  with LspToolSupport {
  override def paginate: Boolean = false

  override protected def executeTyped(input: LspSelectionRangeInput, context: TurnContext): Task[LspSelectionRangeResult] =
    withOpenDocumentTyped[LspSelectionRangeResult](
      input.languageId,
      input.filePath,
      context,
      onError = msg => throw new RuntimeException(msg)
    ) { (session, uri) =>
      val positions = input.positions.map(p => new Position(p.line, p.character))
      session.selectionRange(uri, positions).map { results =>
        LspSelectionRangeResult(
          filePath = input.filePath,
          chains = results.map(r => LspSelectionRangeChain(flatten(r)))
        )
      }
    }

  private def flatten(range: SelectionRange): List[LspRange] = {
    val acc = scala.collection.mutable.ListBuffer.empty[LspRange]
    var cursor: SelectionRange = range
    while (cursor != null) {
      acc += LspRange.fromLsp4j(cursor.getRange)
      cursor = cursor.getParent
    }
    acc.toList
  }
}
