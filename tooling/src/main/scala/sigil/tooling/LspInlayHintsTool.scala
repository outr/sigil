package sigil.tooling

import fabric.rw.*
import org.eclipse.lsp4j.{InlayHint, InlayHintKind, Position, Range}
import rapid.Task
import sigil.TurnContext
import sigil.tool.{ToolExample, ToolInput, ToolName, TypedOutputTool}
import sigil.tooling.types.{LspInlayHintItem, LspInlayHintsResult, LspPosition}

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
final class LspInlayHintsTool(val manager: LspManager) extends TypedOutputTool[LspInlayHintsInput, LspInlayHintsResult](
  name = ToolName("lsp_inlay_hints"),
  description =
    """List inlay hints (inferred types, parameter labels) in a range.
      |
      |`languageId` + `filePath` identify the document.
      |`startLine`/`startCharacter`/`endLine`/`endCharacter` (0-based) bound the range;
      |defaults to the whole file.
      |Each item: `{kind, position, label}` where kind is `type` / `param` / `hint`.""".stripMargin,
  examples = List(
    ToolExample(
      "scala inlay hints for the whole file",
      LspInlayHintsInput(languageId = "scala", filePath = "/abs/path/Foo.scala")
    )
  )
) with LspToolSupport {
  override protected def executeTyped(input: LspInlayHintsInput, context: TurnContext): Task[LspInlayHintsResult] =
    withOpenDocumentTyped[LspInlayHintsResult](
      input.languageId, input.filePath, context,
      onError = msg => throw new RuntimeException(msg)
    ) { (session, uri) =>
      val range = new Range(
        new Position(input.startLine, input.startCharacter),
        new Position(input.endLine, input.endCharacter)
      )
      session.inlayHints(uri, range).map { hints =>
        LspInlayHintsResult(filePath = input.filePath, items = hints.map(toItem))
      }
    }

  private def toItem(hint: InlayHint): LspInlayHintItem = {
    val kind = Option(hint.getKind).map {
      case InlayHintKind.Type      => "type"
      case InlayHintKind.Parameter => "param"
    }.getOrElse("hint")
    val label = hint.getLabel match {
      case lbl if lbl.isLeft => lbl.getLeft
      case lbl =>
        Option(lbl.getRight).map(_.asScala.toList.map(_.getValue).mkString).getOrElse("")
    }
    LspInlayHintItem(kind = kind, position = LspPosition.fromLsp4j(hint.getPosition), label = label)
  }
}
