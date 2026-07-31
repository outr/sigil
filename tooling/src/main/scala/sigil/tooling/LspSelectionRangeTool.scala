package sigil.tooling

import fabric.rw.*
import org.eclipse.lsp4j.{Position, SelectionRange}
import rapid.Task
import sigil.tool.ToolContext
import sigil.tool.{DiscoverySpec, Effect, Freshness, Resolution, Tool, ToolIO, ToolInput, ToolName, ToolProfile, ToolSpec}
import sigil.tooling.types.{LspRange, LspSelectionRangeChain, LspSelectionRangeResult}

case class LspSelectionRangeInput(languageId: String, filePath: String, positions: List[LspSelectionRangeInput.Pos]) extends ToolInput
  derives RW

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
final class LspSelectionRangeTool(val manager: LspManager) extends Tool with LspToolSupport {
  type Input = LspSelectionRangeInput
  type Output = LspSelectionRangeResult
  val io: ToolIO[LspSelectionRangeInput, LspSelectionRangeResult] = ToolIO.derived[LspSelectionRangeInput, LspSelectionRangeResult]

  override val name = ToolName("lsp_selection_range")
  override val description =
    """For each input cursor position, return the chain of progressively-larger semantic
      |regions enclosing it (identifier → expression → statement → method → class …).
      |
      |`languageId` + `filePath` identify the document.
      |`positions` is the list of (line, character) pairs (0-based).
      |Returns `{filePath, chains: [{ranges: [innermost, ..., outermost]}]}` — one chain per input position.""".stripMargin
  val spec: ToolSpec = ToolSpec(
    name = name,
    description = description,
    profile = ToolProfile(effect = Effect.ReadOnly(Freshness.Stable)),
    discovery = DiscoverySpec(
      keywords = Set("lsp", "selection", "expand selection", "smart selection"),
      toolchain = Some("lsp")
    )
  )

  protected def resolve: Resolution[Input, Output] = Resolution.Simple(executeOutput)

  private def executeOutput(input: LspSelectionRangeInput, context: ToolContext): Task[LspSelectionRangeResult] =
    withOpenDocumentOrThrow[LspSelectionRangeResult](
      input.languageId,
      input.filePath,
      context
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
