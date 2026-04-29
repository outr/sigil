package sigil.tooling

import fabric.rw.*
import org.eclipse.lsp4j.DocumentLink
import rapid.Stream
import sigil.TurnContext
import sigil.event.Event
import sigil.tool.{ToolExample, ToolInput, ToolName, TypedTool}

case class LspDocumentLinkInput(languageId: String,
                                filePath: String) extends ToolInput derives RW

/**
 * List clickable document links in a file — URL strings, file paths
 * referenced from comments, asset references, etc. Servers that
 * understand the file's surface (e.g. Markdown servers, HTML
 * servers) provide rich link metadata; servers that don't return
 * an empty list.
 */
final class LspDocumentLinkTool(val manager: LspManager) extends TypedTool[LspDocumentLinkInput](
  name = ToolName("lsp_document_links"),
  description =
    """List the document links the language server has identified in a file.
      |
      |`languageId` + `filePath` identify the document.
      |Each entry shows the link's range and target (if resolved).""".stripMargin,
  examples = List(
    ToolExample(
      "list links in a Markdown file",
      LspDocumentLinkInput(languageId = "markdown", filePath = "/abs/path/README.md")
    )
  )
) with LspToolSupport {
  override protected def executeTyped(input: LspDocumentLinkInput, context: TurnContext): Stream[Event] =
    withOpenDocument(input.languageId, input.filePath, context) { (session, uri) =>
      session.documentLinks(uri).map { links =>
        if (links.isEmpty) "No document links."
        else links.map(render).mkString("\n")
      }
    }

  private def render(link: DocumentLink): String = {
    val r = link.getRange
    val pos = s"${r.getStart.getLine + 1}:${r.getStart.getCharacter + 1}"
    val target = Option(link.getTarget).getOrElse("(unresolved)")
    s"  $pos → $target"
  }
}
