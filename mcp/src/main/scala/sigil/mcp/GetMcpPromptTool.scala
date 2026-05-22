package sigil.mcp

import fabric.rw.*
import fabric.io.JsonFormatter
import rapid.Task
import sigil.TurnContext
import sigil.tool.{TextToolOutput, Tool, ToolInput, ToolName, ToolResult}

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

  val name = ToolName("get_mcp_prompt")
  val description =
    """Fetch a populated prompt template from a registered MCP server. Provide `server`, `prompt` (template name),
      |and any `arguments` the template requires. Returns the server's GetPromptResult JSON.""".stripMargin

  override def executeResult(input: GetMcpPromptInput, context: TurnContext): Task[ToolResult[TextToolOutput]] =
    manager.getPrompt(input.server, input.prompt, input.arguments).map { result =>
      ToolResult.Success(TextToolOutput(JsonFormatter.Default(result)))
    }.handleError { e =>
      Task.pure(ToolResult.failure(s"Get prompt failed: ${e.getMessage}"))
    }
}
