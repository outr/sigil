package sigil.mcp

import fabric.rw.*
import rapid.Task
import sigil.tool.ToolContext
import sigil.tool.{TextToolOutput, Tool, ToolInput, ToolName, ToolResult}

case class RemoveMcpServerInput(name: String) extends ToolInput derives RW

/** Tear down an MCP server's persisted config and active connection. */
final class RemoveMcpServerTool(manager: McpManager) extends Tool {
  type Input  = RemoveMcpServerInput
  type Output = TextToolOutput
  val inputRW  = summon[RW[RemoveMcpServerInput]]
  val outputRW = summon[RW[TextToolOutput]]

  val name = ToolName("remove_mcp_server")
  val description = "Remove a registered MCP server and disconnect any active connection. The persisted config is deleted."

  override def executeResult(input: RemoveMcpServerInput, context: ToolContext): Task[ToolResult[TextToolOutput]] =
    manager.removeConfig(input.name).map { _ =>
      ToolResult.Success(TextToolOutput(s"MCP server '${input.name}' removed."))
    }.handleError { e =>
      Task.pure(ToolResult.failure(s"Failed to remove '${input.name}': ${e.getMessage}"))
    }
}
