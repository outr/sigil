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

case class RemoveMcpServerInput(name: String) extends ToolInput derives RW

/**
 * Tear down an MCP server's persisted config and active connection.
 */
final class RemoveMcpServerTool(manager: McpManager) extends Tool {
  type Input = RemoveMcpServerInput
  type Output = TextToolOutput
  val io: ToolIO[RemoveMcpServerInput, TextToolOutput] = ToolIO.derived[RemoveMcpServerInput, TextToolOutput]

  override val name = ToolName("remove_mcp_server")
  override val description = "Remove a registered MCP server and disconnect any active connection. The persisted config is deleted."
  val spec: ToolSpec = ToolSpec(
    name = name,
    description = description,
    profile = ToolProfile(effect = Effect.Mutating(MutationTargeting.none)),
    discovery = DiscoverySpec(keywords = Set("mcp", "server", "remove", "unregister", "delete", "disconnect"))
  )

  protected def resolve: Resolution[Input, Output] = Resolution.Explicit(executeResult)

  private def executeResult(input: RemoveMcpServerInput, context: ToolContext): Task[ToolResult[TextToolOutput]] =
    manager.removeConfig(input.name).map { _ =>
      ToolResult.Success(TextToolOutput(s"MCP server '${input.name}' removed."))
    }.handleError { e =>
      Task.pure(ToolResult.failure(s"Failed to remove '${input.name}': ${e.getMessage}"))
    }
}
