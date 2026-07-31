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

case class TestMcpServerInput(name: String) extends ToolInput derives RW

/**
 * Force a connection attempt and report success or failure.
 */
final class TestMcpServerTool(manager: McpManager) extends Tool {
  type Input = TestMcpServerInput
  type Output = TextToolOutput
  val io: ToolIO[TestMcpServerInput, TextToolOutput] = ToolIO.derived[TestMcpServerInput, TextToolOutput]

  override val name = ToolName("test_mcp_server")
  override val description = "Connect to a registered MCP server (or use the cached connection if active) and report success or failure."
  val spec: ToolSpec = ToolSpec(
    name = name,
    description = description,
    profile = ToolProfile(effect = Effect.Mutating(MutationTargeting.none)),
    discovery = DiscoverySpec(keywords = Set("mcp", "server", "test", "connection", "check", "verify"))
  )

  protected def resolve: Resolution[Input, Output] = Resolution.Explicit(executeResult)

  private def executeResult(input: TestMcpServerInput, context: ToolContext): Task[ToolResult[TextToolOutput]] =
    manager.test(input.name).map {
      case Right(tools) =>
        ToolResult.Success(TextToolOutput(s"OK — '${input.name}' connected, ${tools.size} tools."))
      case Left(t) =>
        ToolResult.failure(s"FAIL — '${input.name}': ${t.getMessage}")
    }
}
