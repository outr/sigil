package sigil.tooling

import fabric.rw.*
import rapid.Stream
import sigil.TurnContext
import sigil.event.Event
import sigil.tool.{ToolExample, ToolInput, ToolName, TypedTool}

case class LspFoldingRangeInput(languageId: String,
                                filePath: String) extends ToolInput derives RW

/**
 * List foldable regions in a file — class bodies, method bodies,
 * imports blocks, comment blocks, etc. Useful for the agent to
 * compress a long file into a navigable outline before zooming in:
 * "what major sections does this file have, and where do they live?"
 */
final class LspFoldingRangeTool(val manager: LspManager) extends TypedTool[LspFoldingRangeInput](
  name = ToolName("lsp_folding_range"),
  description =
    """List foldable regions in a file (class bodies, methods, import blocks, etc.).
      |
      |`languageId` + `filePath` identify the document.
      |Each entry shows start/end line and the LSP-defined kind (`region`, `comment`,
      |`imports`, etc.) when the server provides it.""".stripMargin,
  examples = List(
    ToolExample(
      "outline foldable regions of a Scala file",
      LspFoldingRangeInput(languageId = "scala", filePath = "/abs/path/Foo.scala")
    )
  )
) with LspToolSupport {
  override protected def executeTyped(input: LspFoldingRangeInput, context: TurnContext): Stream[Event] =
    withOpenDocument(input.languageId, input.filePath, context) { (session, uri) =>
      session.foldingRange(uri).map { ranges =>
        if (ranges.isEmpty) "No folding ranges."
        else ranges.map { r =>
          val kind = Option(r.getKind).getOrElse("region")
          s"  [$kind] lines ${r.getStartLine + 1} – ${r.getEndLine + 1}"
        }.mkString("\n")
      }
    }
}
