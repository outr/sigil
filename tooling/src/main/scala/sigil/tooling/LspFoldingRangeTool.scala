package sigil.tooling

import fabric.rw.*
import rapid.Task
import sigil.TurnContext
import sigil.tool.{ToolExample, ToolInput, ToolName, TypedOutputTool}
import sigil.tooling.types.{LspFoldingRangeItem, LspFoldingRangeResult}

case class LspFoldingRangeInput(languageId: String,
                                filePath: String) extends ToolInput derives RW

/**
 * List foldable regions in a file — class bodies, method bodies,
 * imports blocks, comment blocks, etc. Useful for the agent to
 * compress a long file into a navigable outline before zooming in:
 * "what major sections does this file have, and where do they live?"
 */
final class LspFoldingRangeTool(val manager: LspManager) extends TypedOutputTool[LspFoldingRangeInput, LspFoldingRangeResult](
  name = ToolName("lsp_folding_range"),
  description =
    """List foldable regions in a file (class bodies, methods, import blocks, etc.).
      |
      |`languageId` + `filePath` identify the document.
      |Returns each fold's `kind` (`region` / `comment` / `imports`), 1-based start/end lines.""".stripMargin,
  keywords = Set("lsp", "fold", "folding", "collapse", "sections", "regions", "code structure"),
  examples = List(
    ToolExample(
      "outline foldable regions of a Scala file",
      LspFoldingRangeInput(languageId = "scala", filePath = "/abs/path/Foo.scala")
    )
  )
) with LspToolSupport {
  override protected def executeTyped(input: LspFoldingRangeInput, context: TurnContext): Task[LspFoldingRangeResult] =
    withOpenDocumentTyped[LspFoldingRangeResult](
      input.languageId, input.filePath, context,
      onError = msg => throw new RuntimeException(msg)
    ) { (session, uri) =>
      session.foldingRange(uri).map { ranges =>
        LspFoldingRangeResult(
          filePath = input.filePath,
          ranges = ranges.map { r =>
            LspFoldingRangeItem(
              kind      = Option(r.getKind).getOrElse("region"),
              startLine = r.getStartLine + 1,
              endLine   = r.getEndLine + 1
            )
          }
        )
      }
    }
}
