package sigil.tooling

import fabric.rw.*
import org.eclipse.lsp4j.DocumentLink
import rapid.Task
import sigil.TurnContext
import sigil.tool.{ToolExample, ToolInput, ToolName, TypedOutputTool}
import sigil.tooling.types.{LspDocumentLinkItem, LspDocumentLinkResult, LspPosition}

case class LspDocumentLinkInput(languageId: String,
                                filePath: String) extends ToolInput derives RW

/**
 * List clickable document links in a file — URL strings, file paths
 * referenced from comments, asset references, etc. Servers that
 * understand the file's surface (e.g. Markdown servers, HTML
 * servers) provide rich link metadata; servers that don't return
 * an empty list.
 */
final class LspDocumentLinkTool(val manager: LspManager) extends TypedOutputTool[LspDocumentLinkInput, LspDocumentLinkResult](
  name = ToolName("lsp_document_links"),
  description =
    """List the document links the language server has identified in a file.
      |
      |`languageId` + `filePath` identify the document.
      |Each entry shows the link's start position and target URI (when resolved).""".stripMargin,
  keywords = Set("lsp", "links", "document link", "hyperlink", "navigate"),
  examples = List(
    ToolExample(
      "list links in a Markdown file",
      LspDocumentLinkInput(languageId = "markdown", filePath = "/abs/path/README.md")
    )
  )
) with LspToolSupport {
  override protected def executeTyped(input: LspDocumentLinkInput, context: TurnContext): Task[LspDocumentLinkResult] =
    withOpenDocumentTyped[LspDocumentLinkResult](
      input.languageId, input.filePath, context,
      onError = msg => throw new RuntimeException(msg)
    ) { (session, uri) =>
      session.documentLinks(uri).map { links =>
        LspDocumentLinkResult(filePath = input.filePath, items = links.map(toItem))
      }
    }

  private def toItem(link: DocumentLink): LspDocumentLinkItem =
    LspDocumentLinkItem(
      position = LspPosition.fromLsp4j(link.getRange.getStart),
      target   = Option(link.getTarget)
    )
}
