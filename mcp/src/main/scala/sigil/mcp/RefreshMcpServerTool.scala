package sigil.mcp

import fabric.rw.*
import rapid.Task
import sigil.tool.ToolContext
import sigil.tool.{
  DiscoverySpec,
  Effect,
  MutationTargeting,
  Resolution,
  TextToolOutput,
  Tool,
  ToolIO,
  ToolInput,
  ToolName,
  ToolProfile,
  ToolResult,
  ToolSpec
}

case class RefreshMcpServerInput(name: String) extends ToolInput derives RW

/**
 * Force-refresh the cached tool / resource / prompt list for a server.
 */
final class RefreshMcpServerTool(manager: McpManager) extends Tool {
  type Input = RefreshMcpServerInput
  type Output = TextToolOutput
  val io: ToolIO[RefreshMcpServerInput, TextToolOutput] = ToolIO.derived[RefreshMcpServerInput, TextToolOutput]

  override val name = ToolName("refresh_mcp_server")
  override val description =
    "Force-refresh the cached tool / resource / prompt list for a registered MCP server, bypassing the standard refresh interval."
  val spec: ToolSpec = ToolSpec(
    name = name,
    description = description,
    profile = ToolProfile(effect = Effect.Mutating(MutationTargeting.none)),
    discovery = DiscoverySpec(keywords = Set("mcp", "server", "refresh", "reload", "tools", "cache"))
  )

  protected def resolve: Resolution[Input, Output] = Resolution.Explicit(executeResult)

  private def executeResult(input: RefreshMcpServerInput, context: ToolContext): Task[ToolResult[TextToolOutput]] =
    manager.refresh(input.name).map { tools =>
      ToolResult.Success(TextToolOutput(s"Refreshed '${input.name}' — ${tools.size} tools."))
    }.handleError { e =>
      Task.pure(ToolResult.failure(s"Refresh failed for '${input.name}': ${e.getMessage}"))
    }
}
