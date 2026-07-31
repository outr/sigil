package sigil.tooling

import fabric.rw.*
import rapid.Task
import sigil.tool.ToolContext
import sigil.tool.{DiscoverySpec, Effect, Freshness, Resolution, Tool, ToolIO, ToolInput, ToolName, ToolProfile, ToolSpec}
import sigil.tooling.types.{LspHover, LspHoverResult}

case class LspHoverInput(languageId: String, filePath: String, line: Int, character: Int) extends ToolInput derives RW

/**
 * Returns the hover information at a position — type signature,
 * inferred type, doc comment. The agent's "what is this thing"
 * query, equivalent to mousing over a symbol in an IDE.
 *
 * Markdown-formatted output (most servers ship `MarkupContent`).
 * Servers that respond with the legacy `MarkedString` shape are
 * coalesced into the same plain-string output.
 *
 * Emits `LspHoverResult` — `hover` is `None` when the server returned
 * no hover info at that position.
 */
final class LspHoverTool(val manager: LspManager) extends Tool with LspToolSupport {
  type Input = LspHoverInput
  type Output = LspHoverResult
  val io: ToolIO[LspHoverInput, LspHoverResult] = ToolIO.derived[LspHoverInput, LspHoverResult]

  override val name = ToolName("lsp_hover")
  override val description =
    """Get type signature + documentation at a source position.
      |
      |`languageId` selects the persisted LspServerConfig.
      |`filePath` + `line` + `character` (0-based) point at any character inside the symbol.
      |Returns `Option[{contents, kind, range?}]` — `None` if the server has no hover info there.""".stripMargin
  val spec: ToolSpec = ToolSpec(
    name = name,
    description = description,
    profile = ToolProfile(effect = Effect.ReadOnly(Freshness.Stable)),
    discovery = DiscoverySpec(
      keywords = Set("lsp", "hover", "type", "type info", "info", "what is", "signature", "docs", "documentation", "explain"),
      toolchain = Some("lsp")
    )
  )

  protected def resolve: Resolution[Input, Output] = Resolution.Simple(executeOutput)

  private def executeOutput(input: LspHoverInput, context: ToolContext): Task[LspHoverResult] =
    withOpenDocumentOrThrow[LspHoverResult](
      input.languageId,
      input.filePath,
      context
    ) { (session, uri) =>
      session.hover(uri, input.line, input.character)
        .map(h => LspHoverResult(h.map(LspHover.fromLsp4j)))
    }
}
