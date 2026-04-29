package sigil.tooling

import fabric.rw.*
import org.eclipse.lsp4j.{Position, SelectionRange}
import rapid.Stream
import sigil.TurnContext
import sigil.event.Event
import sigil.tool.{ToolExample, ToolInput, ToolName, TypedTool}

case class LspSelectionRangeInput(languageId: String,
                                  filePath: String,
                                  positions: List[LspSelectionRangeInput.Pos]) extends ToolInput derives RW

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
final class LspSelectionRangeTool(val manager: LspManager) extends TypedTool[LspSelectionRangeInput](
  name = ToolName("lsp_selection_range"),
  description =
    """For each input cursor position, return the chain of progressively-larger semantic
      |regions enclosing it (identifier → expression → statement → method → class …).
      |
      |`languageId` + `filePath` identify the document.
      |`positions` is the list of (line, character) pairs (0-based).""".stripMargin,
  examples = List(
    ToolExample(
      "expand selection at one position",
      LspSelectionRangeInput(
        languageId = "scala", filePath = "/abs/path/Foo.scala",
        positions = List(LspSelectionRangeInput.Pos(line = 10, character = 7))
      )
    )
  )
) with LspToolSupport {
  override protected def executeTyped(input: LspSelectionRangeInput, context: TurnContext): Stream[Event] =
    withOpenDocument(input.languageId, input.filePath, context) { (session, uri) =>
      val positions = input.positions.map(p => new Position(p.line, p.character))
      session.selectionRange(uri, positions).map { results =>
        if (results.isEmpty) "No selection ranges."
        else results.zipWithIndex.map { case (range, idx) =>
          s"position ${idx + 1}:\n${renderChain(range, depth = 0)}"
        }.mkString("\n\n")
      }
    }

  private def renderChain(range: SelectionRange, depth: Int): String = {
    val indent = "  " * depth
    val r = range.getRange
    val line = s"$indent${r.getStart.getLine + 1}:${r.getStart.getCharacter + 1} – ${r.getEnd.getLine + 1}:${r.getEnd.getCharacter + 1}"
    Option(range.getParent) match {
      case None    => line
      case Some(p) => s"$line\n${renderChain(p, depth + 1)}"
    }
  }
}
