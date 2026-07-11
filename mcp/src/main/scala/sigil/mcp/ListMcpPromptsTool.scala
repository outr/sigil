package sigil.mcp

import fabric.rw.*
import rapid.Task
import sigil.tool.ToolContext
import sigil.tool.{TextToolOutput, Tool, ToolInput, ToolName, ToolResult}

case class ListMcpPromptsInput(server: String) extends ToolInput derives RW

/**
 * List the prompt templates advertised by a registered MCP server.
 */
final class ListMcpPromptsTool(manager: McpManager) extends Tool {
  type Input = ListMcpPromptsInput
  type Output = TextToolOutput
  val inputRW = summon[RW[ListMcpPromptsInput]]
  val outputRW = summon[RW[TextToolOutput]]

  val name = ToolName("list_mcp_prompts")
  val description = "List the prompt templates advertised by a registered MCP server, including their argument names."

  override def executeResult(input: ListMcpPromptsInput, context: ToolContext): Task[ToolResult[TextToolOutput]] =
    manager.listPrompts(input.server).map { prompts =>
      val text = if (prompts.isEmpty) "(no prompts advertised)"
      else prompts.map { p =>
        val args = if (p.arguments.isEmpty) ""
        else p.arguments.map { a =>
          val req = if (a.required) "*" else ""
          s"${a.name}$req"
        }.mkString("(", ", ", ")")
        val desc = p.description.map(d => s" — $d").getOrElse("")
        s"- ${p.name}$args$desc"
      }.mkString("\n")
      ToolResult.Success(TextToolOutput(text))
    }.handleError { e =>
      Task.pure(ToolResult.failure(s"List prompts failed: ${e.getMessage}"))
    }
}
