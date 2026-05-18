package sigil.tooling

import fabric.rw.*
import rapid.Task
import sigil.TurnContext
import sigil.tool.{ToolInput, ToolName, TypedOutputTool}
import sigil.tooling.types.LspHover

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
 * Emits `Option[LspHover]` — `None` when the server returned no
 * hover info at that position.
 */
final class LspHoverTool(val manager: LspManager) extends TypedOutputTool[LspHoverInput, Option[LspHover]](
  name = ToolName("lsp_hover"),
  description =
    """Get type signature + documentation at a source position.
      |
      |`languageId` selects the persisted LspServerConfig.
      |`filePath` + `line` + `character` (0-based) point at any character inside the symbol.
      |Returns `Option[{contents, kind, range?}]` — `None` if the server has no hover info there.""".stripMargin,
  keywords = Set("lsp", "hover", "type", "type info", "info", "what is", "signature", "docs", "documentation", "explain")
) with sigil.tool.ReadOnlyExternalTool with LspToolSupport {
  override def paginate: Boolean = false

  override protected def executeTyped(input: LspHoverInput, context: TurnContext): Task[Option[LspHover]] =
    withOpenDocumentTyped[Option[LspHover]](
      input.languageId, input.filePath, context,
      onError = msg => throw new RuntimeException(msg)
    ) { (session, uri) =>
      session.hover(uri, input.line, input.character).map(_.map(LspHover.fromLsp4j))
    }
}
