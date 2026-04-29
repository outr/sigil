package sigil.tooling

import fabric.rw.*
import rapid.{Stream, Task}
import sigil.TurnContext
import sigil.event.Event
import sigil.tool.{ToolExample, ToolInput, ToolName, TypedTool}

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
final class LspWorkspaceSymbolsTool(val manager: LspManager) extends TypedTool[LspWorkspaceSymbolsInput](
  name = ToolName("lsp_workspace_symbols"),
  description =
    """Search for symbols by name across the workspace.
      |
      |`languageId` selects the persisted LspServerConfig.
      |`projectRoot` is the project's root path (used to spawn / resolve the session).
      |`query` is the search string — fuzzy / substring depending on server config.
      |`maxResults` (default 100) caps the response.""".stripMargin,
  examples = List(
    ToolExample(
      "find symbols matching 'Provider'",
      LspWorkspaceSymbolsInput(languageId = "scala", projectRoot = "/abs/path/myproject", query = "Provider")
    )
  )
) with LspToolSupport {
  override protected def executeTyped(input: LspWorkspaceSymbolsInput, context: TurnContext): Stream[Event] = {
    val task = manager.configFor(input.languageId).flatMap {
      case None =>
        Task.pure(reply(context, s"No LspServerConfig persisted for '${input.languageId}'.", isError = true))
      case Some(_) =>
        manager.session(input.languageId, input.projectRoot).flatMap { session =>
          session.workspaceSymbols(input.query).map { hits =>
            if (hits.isEmpty) reply(context, "No symbols found.", isError = false)
            else {
              val capped = hits.take(input.maxResults)
              val rendered = capped.map { h =>
                val container = h.containerName.map(c => s" in $c").getOrElse("")
                val pos = h.range.map(r => s" @${r.getStart.getLine + 1}:${r.getStart.getCharacter + 1}").getOrElse("")
                s"  [${h.kind}] ${h.name}$container — ${h.uri}$pos"
              }.mkString("\n")
              val tail =
                if (hits.size > input.maxResults) s"\n... (${hits.size - input.maxResults} more)"
                else ""
              reply(context, rendered + tail, isError = false)
            }
          }
        }.handleError { e =>
          Task.pure(reply(context, s"LSP error: ${e.getMessage}", isError = true))
        }
    }
    Stream.force(task.map(Stream.emit))
  }
}
