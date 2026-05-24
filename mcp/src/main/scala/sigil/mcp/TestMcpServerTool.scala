package sigil.mcp

import fabric.rw.*
import rapid.Task
import sigil.tool.ToolContext
import sigil.tool.{TextToolOutput, Tool, ToolInput, ToolName, ToolResult}

case class TestMcpServerInput(name: String) extends ToolInput derives RW

/** Force a connection attempt and report success or failure. */
final class TestMcpServerTool(manager: McpManager) extends Tool {
  type Input  = TestMcpServerInput
  type Output = TextToolOutput
  val inputRW  = summon[RW[TestMcpServerInput]]
  val outputRW = summon[RW[TextToolOutput]]

  val name = ToolName("test_mcp_server")
  val description = "Connect to a registered MCP server (or use the cached connection if active) and report success or failure."

  override def executeResult(input: TestMcpServerInput, context: ToolContext): Task[ToolResult[TextToolOutput]] =
    manager.test(input.name).map {
      case Right(tools) =>
        ToolResult.Success(TextToolOutput(s"OK — '${input.name}' connected, ${tools.size} tools."))
      case Left(t) =>
        ToolResult.failure(s"FAIL — '${input.name}': ${t.getMessage}")
    }
}
