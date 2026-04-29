package sigil.tooling

import fabric.rw.*
import org.eclipse.lsp4j.{DocumentSymbol, SymbolInformation}
import org.eclipse.lsp4j.jsonrpc.messages.{Either => LspEither}
import rapid.Stream
import sigil.TurnContext
import sigil.event.Event
import sigil.tool.{ToolExample, ToolInput, ToolName, TypedTool}

import scala.jdk.CollectionConverters.*

case class LspDocumentSymbolsInput(languageId: String,
                                   filePath: String) extends ToolInput derives RW

/**
 * Outline the symbols in a single file — classes, traits, methods,
 * fields. Hierarchical when the server returns `DocumentSymbol`
 * (containers nested under their owners), flat when it returns
 * the legacy `SymbolInformation` shape.
 *
 * The agent uses this for "what's in this file" before edits, and
 * to locate a symbol by name without a workspace-wide search.
 */
final class LspDocumentSymbolsTool(val manager: LspManager) extends TypedTool[LspDocumentSymbolsInput](
  name = ToolName("lsp_document_symbols"),
  description =
    """List the symbols (classes / methods / fields / etc.) defined in a file.
      |
      |`languageId` + `filePath` identify the document.
      |Output is hierarchical when the server supports `DocumentSymbol`;
      |flat for legacy `SymbolInformation` results.""".stripMargin,
  examples = List(
    ToolExample(
      "outline a Scala file",
      LspDocumentSymbolsInput(languageId = "scala", filePath = "/abs/path/Foo.scala")
    )
  )
) with LspToolSupport {
  override protected def executeTyped(input: LspDocumentSymbolsInput, context: TurnContext): Stream[Event] =
    withOpenDocument(input.languageId, input.filePath, context) { (session, uri) =>
      session.documentSymbols(uri).map { symbols =>
        if (symbols.isEmpty) "No symbols."
        else symbols.map(renderTop).mkString("\n")
      }
    }

  private def renderTop(either: LspEither[SymbolInformation, DocumentSymbol]): String =
    if (either.isLeft) renderInfo(either.getLeft, depth = 0)
    else renderDoc(either.getRight, depth = 0)

  @annotation.nowarn("cat=deprecation")
  private def renderInfo(si: SymbolInformation, depth: Int): String = {
    val indent = "  " * depth
    val loc = si.getLocation
    val r = if (loc != null) loc.getRange else null
    val pos = if (r != null) s" @${r.getStart.getLine + 1}:${r.getStart.getCharacter + 1}" else ""
    s"$indent[${si.getKind}] ${si.getName}$pos"
  }

  private def renderDoc(ds: DocumentSymbol, depth: Int): String = {
    val indent = "  " * depth
    val r = ds.getRange
    val pos = s" @${r.getStart.getLine + 1}:${r.getStart.getCharacter + 1}"
    val head = s"$indent[${ds.getKind}] ${ds.getName}$pos"
    val children = Option(ds.getChildren).map(_.asScala.toList).getOrElse(Nil)
    if (children.isEmpty) head
    else (head :: children.map(renderDoc(_, depth + 1))).mkString("\n")
  }
}
