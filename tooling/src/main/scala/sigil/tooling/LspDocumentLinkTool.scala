package sigil.tooling

import fabric.rw.*
import org.eclipse.lsp4j.DocumentLink
import rapid.Task
import sigil.tool.ToolContext
import sigil.tool.{DiscoverySpec, Effect, Freshness, Resolution, Tool, ToolIO, ToolInput, ToolName, ToolProfile, ToolSpec}
import sigil.tooling.types.{LspDocumentLinkItem, LspDocumentLinkResult, LspPosition}

case class LspDocumentLinkInput(languageId: String, filePath: String) extends ToolInput derives RW

/**
 * List clickable document links in a file — URL strings, file paths
 * referenced from comments, asset references, etc. Servers that
 * understand the file's surface (e.g. Markdown servers, HTML
 * servers) provide rich link metadata; servers that don't return
 * an empty list.
 */
final class LspDocumentLinkTool(val manager: LspManager) extends Tool with LspToolSupport {
  type Input = LspDocumentLinkInput
  type Output = LspDocumentLinkResult
  val io: ToolIO[LspDocumentLinkInput, LspDocumentLinkResult] = ToolIO.derived[LspDocumentLinkInput, LspDocumentLinkResult]
  override val name = ToolName("lsp_document_links")
  override val description =
    """List the document links the language server has identified in a file.
      |
      |`languageId` + `filePath` identify the document.
      |Each entry shows the link's start position and target URI (when resolved).""".stripMargin
  val spec: ToolSpec = ToolSpec(
    name = name,
    description = description,
    profile = ToolProfile(effect = Effect.ReadOnly(Freshness.Stable)),
    discovery = DiscoverySpec(
      keywords = Set("lsp", "links", "document link", "hyperlink", "navigate"),
      toolchain = Some("lsp")
    )
  )

  protected def resolve: Resolution[Input, Output] = Resolution.Simple(executeOutput)

  private def executeOutput(input: LspDocumentLinkInput, context: ToolContext): Task[LspDocumentLinkResult] =
    withOpenDocumentOrThrow[LspDocumentLinkResult](
      input.languageId,
      input.filePath,
      context
    ) { (session, uri) =>
      session.documentLinks(uri).map { links =>
        LspDocumentLinkResult(filePath = input.filePath, items = links.map(toItem))
      }
    }

  private def toItem(link: DocumentLink): LspDocumentLinkItem =
    LspDocumentLinkItem(
      position = LspPosition.fromLsp4j(link.getRange.getStart),
      target = Option(link.getTarget)
    )
}
