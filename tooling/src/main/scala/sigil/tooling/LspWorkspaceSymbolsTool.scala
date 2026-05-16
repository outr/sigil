package sigil.tooling

import fabric.rw.*
import rapid.{Stream, Task}
import sigil.TurnContext
import sigil.tool.output.{Node, PaginatedTool}
import sigil.tool.{ToolExample, ToolInput, ToolName}
import sigil.tooling.types.{LspPosition, LspWorkspaceSymbol}

case class LspWorkspaceSymbolsInput(languageId: String,
                                    projectRoot: String,
                                    query: String,
                                    maxResults: Int = 100) extends ToolInput derives RW

/**
 * Search for symbols by name across the entire workspace.
 * Paginated: top-level nodes are [[LspWorkspaceSymbol]] hits in
 * the server's returned order. The first page is inline; the
 * rest paginate via `next_page`.
 */
final class LspWorkspaceSymbolsTool(val manager: LspManager) extends PaginatedTool[LspWorkspaceSymbolsInput, LspWorkspaceSymbol](
  name = ToolName("lsp_workspace_symbols"),
  description0 =
    """Search for symbols by name across the workspace.
      |
      |`languageId` selects the persisted LspServerConfig.
      |`projectRoot` is the project's root path (used to spawn / resolve the session).
      |`query` is the search string — fuzzy / substring depending on server config.
      |`maxResults` (default 100) caps the response.
      |
      |Top-level nodes are symbol hits with `{kind, name, container, uri, position}`.""".stripMargin,
  keywords = Set(
    "lsp", "workspace", "symbols", "symbol", "find symbol", "search",
    "class", "method", "function", "definition", "signature", "structure",
    "examine", "inspect", "analyze", "explore", "browse", "lookup",
    "code", "codebase", "semantic", "index", "catalog",
    "scala", "language", "navigate", "project"
  ),
  examples = List(
    ToolExample(
      "find symbols matching 'Provider'",
      LspWorkspaceSymbolsInput(languageId = "scala", projectRoot = "/abs/path/myproject", query = "Provider")
    )
  )
) with sigil.tool.ReadOnlyExternalTool with LspToolSupport {

  override protected def executeStream(input: LspWorkspaceSymbolsInput, context: TurnContext): Stream[Node[LspWorkspaceSymbol]] =
    Stream.force(
      withSessionTyped[Stream[Node[LspWorkspaceSymbol]]](
        input.languageId, input.projectRoot, context,
        onError = _ => Stream.empty
      ) { (session, _, _) =>
        session.workspaceSymbols(input.query).map { hits =>
          val capped = hits.take(input.maxResults).map { h =>
            LspWorkspaceSymbol(
              kind      = Option(h.kind).map(_.toString.toLowerCase).getOrElse("unknown"),
              name      = h.name,
              container = h.containerName,
              uri       = h.uri,
              position  = h.range.map(r => LspPosition.fromLsp4j(r.getStart))
            )
          }
          Stream.fromIterator(Task.pure(capped.iterator.map(Node.leaf(_))))
        }
      }
    )
}
