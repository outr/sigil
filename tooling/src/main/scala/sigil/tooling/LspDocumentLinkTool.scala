package sigil.tooling

import fabric.rw.*
import org.eclipse.lsp4j.DocumentLink
import rapid.Task
import sigil.tool.ToolContext
import sigil.tool.{Tool, ToolInput, ToolName}
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
final class LspDocumentLinkTool(val manager: LspManager) extends Tool
  with sigil.tool.ReadOnlyExternalTool with LspToolSupport {
  type Input  = LspDocumentLinkInput
  type Output = LspDocumentLinkResult
  val inputRW  = summon[RW[LspDocumentLinkInput]]
  val outputRW = summon[RW[LspDocumentLinkResult]]
  val name = ToolName("lsp_document_links")
  val description =
    """List the document links the language server has identified in a file.
      |
      |`languageId` + `filePath` identify the document.
      |Each entry shows the link's start position and target URI (when resolved).""".stripMargin
  override val keywords = Set("lsp", "links", "document link", "hyperlink", "navigate")


  override def executeOutput(input: LspDocumentLinkInput, context: ToolContext): Task[LspDocumentLinkResult] =
    withOpenDocumentOrThrow[LspDocumentLinkResult](
      input.languageId, input.filePath, context
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
