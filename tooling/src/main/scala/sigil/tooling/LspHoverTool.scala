package sigil.tooling

import fabric.rw.*
import rapid.Task
import sigil.TurnContext
import sigil.tool.{Tool, ToolInput, ToolName}
import sigil.tooling.types.{LspHover, LspHoverResult}

case class LspHoverInput(languageId: String,
                         filePath: String,
                         line: Int,
                         character: Int) extends ToolInput derives RW

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
final class LspHoverTool(val manager: LspManager) extends Tool
  with sigil.tool.ReadOnlyExternalTool with LspToolSupport {
  type Input  = LspHoverInput
  type Output = LspHoverResult
  val inputRW  = summon[RW[LspHoverInput]]
  val outputRW = summon[RW[LspHoverResult]]

  val name = ToolName("lsp_hover")
  val description =
    """Get type signature + documentation at a source position.
      |
      |`languageId` selects the persisted LspServerConfig.
      |`filePath` + `line` + `character` (0-based) point at any character inside the symbol.
      |Returns `Option[{contents, kind, range?}]` — `None` if the server has no hover info there.""".stripMargin
  override val keywords = Set("lsp", "hover", "type", "type info", "info", "what is", "signature", "docs", "documentation", "explain")


  override def executeOutput(input: LspHoverInput, context: TurnContext): Task[LspHoverResult] =
    withOpenDocumentOrThrow[LspHoverResult](
      input.languageId, input.filePath, context
    ) { (session, uri) =>
      session.hover(uri, input.line, input.character)
        .map(h => LspHoverResult(h.map(LspHover.fromLsp4j)))
    }
}
