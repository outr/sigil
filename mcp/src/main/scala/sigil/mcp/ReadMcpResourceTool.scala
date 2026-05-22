package sigil.mcp

import fabric.rw.*
import fabric.io.JsonFormatter
import rapid.Task
import sigil.TurnContext
import sigil.tool.{TextToolOutput, Tool, ToolInput, ToolName, ToolResult}

case class ReadMcpResourceInput(server: String, uri: String) extends ToolInput derives RW

/** Read a resource from a registered MCP server by URI. */
final class ReadMcpResourceTool(manager: McpManager) extends Tool {
  type Input  = ReadMcpResourceInput
  type Output = TextToolOutput
  val inputRW  = summon[RW[ReadMcpResourceInput]]
  val outputRW = summon[RW[TextToolOutput]]

  val name = ToolName("read_mcp_resource")
  val description =
    """Fetch the contents of a resource from a registered MCP server. The server identifies the resource by URI;
      |use list_mcp_servers + (server-specific) discovery to learn what URIs are available.""".stripMargin

  override def executeResult(input: ReadMcpResourceInput, context: TurnContext): Task[ToolResult[TextToolOutput]] =
    manager.readResource(input.server, input.uri).map { result =>
      ToolResult.Success(TextToolOutput(JsonFormatter.Default(result)))
    }.handleError { e =>
      Task.pure(ToolResult.failure(s"Read resource failed: ${e.getMessage}"))
    }
}
