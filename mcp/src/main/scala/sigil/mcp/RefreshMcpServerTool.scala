package sigil.mcp

import fabric.rw.*
import rapid.Task
import sigil.tool.ToolContext
import sigil.tool.{TextToolOutput, Tool, ToolInput, ToolName, ToolResult}

case class RefreshMcpServerInput(name: String) extends ToolInput derives RW

/** Force-refresh the cached tool / resource / prompt list for a server. */
final class RefreshMcpServerTool(manager: McpManager) extends Tool {
  type Input  = RefreshMcpServerInput
  type Output = TextToolOutput
  val inputRW  = summon[RW[RefreshMcpServerInput]]
  val outputRW = summon[RW[TextToolOutput]]

  val name = ToolName("refresh_mcp_server")
  val description = "Force-refresh the cached tool / resource / prompt list for a registered MCP server, bypassing the standard refresh interval."

  override def executeResult(input: RefreshMcpServerInput, context: ToolContext): Task[ToolResult[TextToolOutput]] =
    manager.refresh(input.name).map { tools =>
      ToolResult.Success(TextToolOutput(s"Refreshed '${input.name}' — ${tools.size} tools."))
    }.handleError { e =>
      Task.pure(ToolResult.failure(s"Refresh failed for '${input.name}': ${e.getMessage}"))
    }
}
