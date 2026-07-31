package sigil.tooling

import fabric.rw.*
import rapid.Task
import sigil.tool.ToolContext
import sigil.tool.{DiscoverySpec, Effect, Freshness, TextToolOutput, Tool, ToolInput, ToolName, ToolProfile, ToolResult, ToolSpec}
import sigil.tooling.types.{LspPosition, LspWorkspaceSymbol}

case class LspWorkspaceSymbolsInput(languageId: String,
                                    projectRoot: String,
                                    query: String,
                                    maxResults: Int = 100) extends ToolInput derives RW

/**
 * Search for symbols by name across the workspace. Returns the matching
 * symbol hits — `kind name (container) — uri` — as plain text straight
 * into the agent's context, bounded by `maxResults`.
 */
final class LspWorkspaceSymbolsTool(val manager: LspManager)
    extends Tool with LspToolSupport {
  type Input  = LspWorkspaceSymbolsInput
  type Output = TextToolOutput
  val inputRW  = summon[RW[LspWorkspaceSymbolsInput]]
  val outputRW = summon[RW[TextToolOutput]]

  override val name = ToolName("lsp_workspace_symbols")
  override val description =
    """Search for symbols by name across the workspace.
      |
      |`languageId` selects the persisted LspServerConfig.
      |`projectRoot` is the project's root path (used to spawn / resolve the session).
      |`query` is the search string — fuzzy / substring depending on server config.
      |`maxResults` (default 100) caps the response.
      |
      |Returns one line per hit: `kind name (container) — uri`.""".stripMargin

  val spec: ToolSpec = ToolSpec(
    name = name,
    description = description,
    profile = ToolProfile(effect = Effect.ReadOnly(Freshness.Stable)),
    discovery = DiscoverySpec(
      keywords = Set(
        "lsp", "workspace", "symbols", "symbol", "find symbol", "search",
        "class", "method", "function", "definition", "signature", "structure",
        "examine", "inspect", "analyze", "explore", "browse", "lookup",
        "code", "codebase", "semantic", "index", "catalog",
        "scala", "language", "navigate", "project"
      ),
      toolchain = Some("lsp")
    )
  )

  override def executeResult(input: LspWorkspaceSymbolsInput, context: ToolContext): Task[ToolResult[TextToolOutput]] =
    withSessionTyped[ToolResult[TextToolOutput]](
      input.languageId, input.projectRoot, context,
      onError = msg => ToolResult.failure(msg)
    ) { (session, _, _) =>
      session.workspaceSymbols(input.query).map { hits =>
        val lines = hits.take(input.maxResults).map { h =>
          val kind      = Option(h.kind).map(_.toString.toLowerCase).getOrElse("unknown")
          val container = Option(h.containerName).filter(_.nonEmpty).map(c => s" ($c)").getOrElse("")
          val pos       = h.range.map(_.getStart).map(s => s":${s.getLine + 1}").getOrElse("")
          s"$kind ${h.name}$container — ${h.uri}$pos"
        }
        ToolResult.Success(TextToolOutput(if (lines.isEmpty) "(no symbols)" else lines.mkString("\n")))
      }
    }
}
