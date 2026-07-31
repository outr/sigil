package sigil.mcp

import fabric.rw.*
import fabric.io.JsonFormatter
import rapid.Task
import sigil.tool.ToolContext
import sigil.tool.{DiscoverySpec, Effect, Freshness, TextToolOutput, Tool, ToolInput, ToolName, ToolProfile, ToolResult, ToolSpec}

case class GetMcpPromptInput(server: String,
                             prompt: String,
                             arguments: Map[String, String] = Map.empty) extends ToolInput derives RW

/**
 * Fetch a populated prompt template from a registered MCP server.
 * The result is the raw `GetPromptResult` shape (description +
 * messages array); apps decide how to splice it into their context.
 */
final class GetMcpPromptTool(manager: McpManager) extends Tool {
  type Input  = GetMcpPromptInput
  type Output = TextToolOutput
  val inputRW  = summon[RW[GetMcpPromptInput]]
  val outputRW = summon[RW[TextToolOutput]]

  override val name = ToolName("get_mcp_prompt")
  override val description =
    """Fetch a populated prompt template from a registered MCP server. Provide `server`, `prompt` (template name),
      |and any `arguments` the template requires. Returns the server's GetPromptResult JSON.""".stripMargin
  val spec: ToolSpec = ToolSpec(
    name = name,
    description = description,
    profile = ToolProfile(effect = Effect.ReadOnly(Freshness.Stable)),
    discovery = DiscoverySpec(keywords = Set("mcp", "prompt", "get", "template", "render", "server"))
  )

  override def executeResult(input: GetMcpPromptInput, context: ToolContext): Task[ToolResult[TextToolOutput]] =
    manager.getPrompt(input.server, input.prompt, input.arguments).map { result =>
      ToolResult.Success(TextToolOutput(JsonFormatter.Default(result)))
    }.handleError { e =>
      Task.pure(ToolResult.failure(s"Get prompt failed: ${e.getMessage}"))
    }
}
