package sigil.tooling

import fabric.rw.*
import rapid.Task
import sigil.TurnContext
import sigil.tool.{ToolExample, ToolInput, ToolName, TypedOutputTool}
import sigil.tooling.types.{LspPosition, LspWorkspaceSymbol, LspWorkspaceSymbolsResult}

import java.io.File

case class LspWorkspaceSymbolsInput(languageId: String,
                                    projectRoot: String,
                                    query: String,
                                    maxResults: Int = 100) extends ToolInput derives RW

/**
 * Search for symbols by name across the entire workspace. The agent
 * uses this for "find me anything called Foo" — equivalent to an
 * IDE's project-wide symbol picker.
 *
 * Different from `lsp_find_references` which needs a starting
 * position; this one's a free-form name search. Servers usually
 * support fuzzy / substring matching depending on configuration.
 */
final class LspWorkspaceSymbolsTool(val manager: LspManager) extends TypedOutputTool[LspWorkspaceSymbolsInput, LspWorkspaceSymbolsResult](
  name = ToolName("lsp_workspace_symbols"),
  description =
    """Search for symbols by name across the workspace.
      |
      |`languageId` selects the persisted LspServerConfig.
      |`projectRoot` is the project's root path (used to spawn / resolve the session).
      |`query` is the search string — fuzzy / substring depending on server config.
      |`maxResults` (default 100) caps the response.
      |Returns `{query, items: [{kind, name, container, uri, position}], totalCount, truncated}`.""".stripMargin,
  examples = List(
    ToolExample(
      "find symbols matching 'Provider'",
      LspWorkspaceSymbolsInput(languageId = "scala", projectRoot = "/abs/path/myproject", query = "Provider")
    )
  )
) with LspToolSupport {
  override protected def executeTyped(input: LspWorkspaceSymbolsInput, context: TurnContext): Task[LspWorkspaceSymbolsResult] =
    withSessionTyped[LspWorkspaceSymbolsResult](
      input.languageId, input.projectRoot, context,
      onError = msg => throw new RuntimeException(msg)
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
        LspWorkspaceSymbolsResult(
          query      = input.query,
          items      = capped,
          totalCount = hits.size,
          truncated  = hits.size > input.maxResults
        )
      }
    }
}
