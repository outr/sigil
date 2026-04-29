package sigil.tooling

import fabric.rw.*
import org.eclipse.lsp4j.{InlayHint, InlayHintKind, Position, Range}
import rapid.Stream
import sigil.TurnContext
import sigil.event.Event
import sigil.tool.{ToolExample, ToolInput, ToolName, TypedTool}

import scala.jdk.CollectionConverters.*

case class LspInlayHintsInput(languageId: String,
                              filePath: String,
                              startLine: Int = 0,
                              startCharacter: Int = 0,
                              endLine: Int = Int.MaxValue,
                              endCharacter: Int = 0) extends ToolInput derives RW

/**
 * List inlay hints in a range — inferred type annotations, parameter
 * name labels, etc. Most servers gate these on client capability
 * (which the framework declares); when present they save the agent
 * from having to call hover at every position to reconstruct types.
 *
 * Default range covers the whole file (`startLine=0, endLine=∞`).
 */
final class LspInlayHintsTool(val manager: LspManager) extends TypedTool[LspInlayHintsInput](
  name = ToolName("lsp_inlay_hints"),
  description =
    """List inlay hints (inferred types, parameter labels) in a range.
      |
      |`languageId` + `filePath` identify the document.
      |`startLine`/`startCharacter`/`endLine`/`endCharacter` (0-based) bound the range;
      |defaults to the whole file.""".stripMargin,
  examples = List(
    ToolExample(
      "scala inlay hints for the whole file",
      LspInlayHintsInput(languageId = "scala", filePath = "/abs/path/Foo.scala")
    )
  )
) with LspToolSupport {
  override protected def executeTyped(input: LspInlayHintsInput, context: TurnContext): Stream[Event] =
    withOpenDocument(input.languageId, input.filePath, context) { (session, uri) =>
      val range = new Range(
        new Position(input.startLine, input.startCharacter),
        new Position(input.endLine, input.endCharacter)
      )
      session.inlayHints(uri, range).map { hints =>
        if (hints.isEmpty) "No inlay hints."
        else hints.map(render).mkString("\n")
      }
    }

  private def render(hint: InlayHint): String = {
    val pos = s"${hint.getPosition.getLine + 1}:${hint.getPosition.getCharacter + 1}"
    val kind = Option(hint.getKind).map {
      case InlayHintKind.Type      => "type"
      case InlayHintKind.Parameter => "param"
    }.getOrElse("hint")
    val label = hint.getLabel match {
      case lbl if lbl.isLeft => lbl.getLeft
      case lbl =>
        Option(lbl.getRight).map(_.asScala.toList.map(_.getValue).mkString).getOrElse("")
    }
    s"  [$kind] $pos: $label"
  }
}
