package sigil.tooling

import fabric.rw.*
import org.eclipse.lsp4j.{DocumentSymbol, SymbolInformation}
import org.eclipse.lsp4j.jsonrpc.messages.{Either => LspEither}
import rapid.Task
import sigil.tool.ToolContext
import sigil.tool.{Tool, ToolInput, ToolName}
import sigil.tooling.types.{LspDocumentSymbolEntry, LspDocumentSymbolsResult, LspPosition}

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
 *
 * Output flattens hierarchy into a depth-indexed list so consumers
 * walk one stream and re-render indentation from `depth`.
 */
final class LspDocumentSymbolsTool(val manager: LspManager) extends Tool
  with sigil.tool.ReadOnlyExternalTool with LspToolSupport {
  type Input  = LspDocumentSymbolsInput
  type Output = LspDocumentSymbolsResult
  val inputRW  = summon[RW[LspDocumentSymbolsInput]]
  val outputRW = summon[RW[LspDocumentSymbolsResult]]
  val name = ToolName("lsp_document_symbols")
  val description =
    """List the symbols (classes / methods / fields / etc.) defined in a file.
      |
      |`languageId` + `filePath` identify the document.
      |Returns `{filePath, entries: [{kind, name, position, depth}]}` — `depth = 0` is top-level.""".stripMargin
  override val keywords = Set(
    "lsp", "document", "symbols", "symbol", "outline", "structure",
    "what's in this file", "classes", "methods", "members",
    "examine", "inspect", "analyze", "review", "explore",
    "code", "semantic", "scala", "language", "navigate"
  )


  override def executeOutput(input: LspDocumentSymbolsInput,
                             context: ToolContext): Task[LspDocumentSymbolsResult] =
    withOpenDocumentOrThrow[LspDocumentSymbolsResult](
      input.languageId, input.filePath, context
    ) { (session, uri) =>
      session.documentSymbols(uri).map { symbols =>
        val entries = symbols.flatMap { either =>
          if (either.isLeft) flattenInfo(either.getLeft, depth = 0)
          else flattenDoc(either.getRight, depth = 0)
        }
        LspDocumentSymbolsResult(filePath = input.filePath, entries = entries)
      }
    }

  @annotation.nowarn("cat=deprecation")
  private def flattenInfo(si: SymbolInformation, depth: Int): List[LspDocumentSymbolEntry] = {
    val pos = Option(si.getLocation).flatMap(l => Option(l.getRange).map(_.getStart))
      .map(LspPosition.fromLsp4j).getOrElse(LspPosition(0, 0))
    List(LspDocumentSymbolEntry(
      kind     = Option(si.getKind).map(_.toString.toLowerCase).getOrElse("unknown"),
      name     = si.getName,
      position = pos,
      depth    = depth
    ))
  }

  private def flattenDoc(ds: DocumentSymbol, depth: Int): List[LspDocumentSymbolEntry] = {
    val head = LspDocumentSymbolEntry(
      kind     = Option(ds.getKind).map(_.toString.toLowerCase).getOrElse("unknown"),
      name     = ds.getName,
      position = LspPosition.fromLsp4j(ds.getRange.getStart),
      depth    = depth
    )
    val tail = Option(ds.getChildren).map(_.asScala.toList).getOrElse(Nil)
      .flatMap(flattenDoc(_, depth + 1))
    head :: tail
  }
}
