package sigil.tooling

import fabric.rw.*
import rapid.Task
import sigil.tool.ToolContext
import sigil.tool.{DiscoverySpec, Effect, Freshness, Resolution, Tool, ToolIO, ToolInput, ToolName, ToolProfile, ToolSpec}
import sigil.tooling.types.{LspFoldingRangeItem, LspFoldingRangeResult}

case class LspFoldingRangeInput(languageId: String, filePath: String) extends ToolInput derives RW

/**
 * List foldable regions in a file — class bodies, method bodies,
 * imports blocks, comment blocks, etc. Useful for the agent to
 * compress a long file into a navigable outline before zooming in:
 * "what major sections does this file have, and where do they live?"
 */
final class LspFoldingRangeTool(val manager: LspManager) extends Tool with LspToolSupport {
  type Input = LspFoldingRangeInput
  type Output = LspFoldingRangeResult
  val io: ToolIO[LspFoldingRangeInput, LspFoldingRangeResult] = ToolIO.derived[LspFoldingRangeInput, LspFoldingRangeResult]
  override val name = ToolName("lsp_folding_range")
  override val description =
    """List foldable regions in a file (class bodies, methods, import blocks, etc.).
      |
      |`languageId` + `filePath` identify the document.
      |Returns each fold's `kind` (`region` / `comment` / `imports`), 1-based start/end lines.""".stripMargin
  val spec: ToolSpec = ToolSpec(
    name = name,
    description = description,
    profile = ToolProfile(effect = Effect.ReadOnly(Freshness.Stable)),
    discovery = DiscoverySpec(
      keywords = Set("lsp", "fold", "folding", "collapse", "sections", "regions", "code structure"),
      toolchain = Some("lsp")
    )
  )

  protected def resolve: Resolution[Input, Output] = Resolution.Simple(executeOutput)

  private def executeOutput(input: LspFoldingRangeInput, context: ToolContext): Task[LspFoldingRangeResult] =
    withOpenDocumentOrThrow[LspFoldingRangeResult](
      input.languageId,
      input.filePath,
      context
    ) { (session, uri) =>
      session.foldingRange(uri).map { ranges =>
        LspFoldingRangeResult(
          filePath = input.filePath,
          ranges = ranges.map { r =>
            LspFoldingRangeItem(
              kind = Option(r.getKind).getOrElse("region"),
              startLine = r.getStartLine + 1,
              endLine = r.getEndLine + 1
            )
          }
        )
      }
    }
}
