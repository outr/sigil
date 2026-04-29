package sigil.tooling

import fabric.rw.*
import org.eclipse.lsp4j.{Hover, MarkupContent}
import rapid.Stream
import sigil.TurnContext
import sigil.event.Event
import sigil.tool.{ToolExample, ToolInput, ToolName, TypedTool}

import scala.jdk.CollectionConverters.*

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
 */
final class LspHoverTool(val manager: LspManager) extends TypedTool[LspHoverInput](
  name = ToolName("lsp_hover"),
  description =
    """Get type signature + documentation at a source position.
      |
      |`languageId` selects the persisted LspServerConfig.
      |`filePath` + `line` + `character` (0-based) point at any character inside the symbol.
      |Returns markdown-formatted hover content (type, inferred signature, doc comments).""".stripMargin,
  examples = List(
    ToolExample(
      "scala hover on a symbol",
      LspHoverInput(languageId = "scala", filePath = "/abs/path/Foo.scala", line = 10, character = 5)
    )
  )
) with LspToolSupport {
  override protected def executeTyped(input: LspHoverInput, context: TurnContext): Stream[Event] =
    withOpenDocument(input.languageId, input.filePath, context) { (session, uri) =>
      session.hover(uri, input.line, input.character).map(render)
    }

  private def render(hover: Option[Hover]): String = hover match {
    case None    => "No hover information."
    case Some(h) =>
      val contents = h.getContents
      if (contents == null) "No hover information."
      else if (contents.isLeft) {
        contents.getLeft.asScala.map { either =>
          if (either.isLeft) either.getLeft else either.getRight.getValue
        }.mkString("\n\n")
      } else {
        val mc: MarkupContent = contents.getRight
        if (mc == null || mc.getValue == null) "No hover information." else mc.getValue
      }
  }
}
