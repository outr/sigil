package sigil.mcp

import fabric.Json
import fabric.define.Definition
import fabric.rw.*
import lightdb.id.Id
import lightdb.time.Timestamp
import rapid.Task
import sigil.{SpaceId, TurnContext}
import sigil.participant.{AgentParticipantId, ParticipantId}
import sigil.provider.Mode
import sigil.tool.{JsonInput, JsonSchemaToDefinition, TextToolOutput, Tool, ToolExample, ToolName, ToolResult}

/**
 * Sigil [[Tool]] backed by an MCP-server-side tool. The wire schema
 * advertised by the server is surfaced directly to the LLM via
 * `inputDefinition` — Sigil generates none of its own. The LLM's
 * arguments arrive as fabric `Json` (wrapped in [[JsonInput]]); the
 * call body fans them out via [[McpManager.callTool]] and renders
 * the server's `CallToolResult` into a [[TextToolOutput]].
 *
 * The display name in `name` includes the server's `prefix` (so two
 * servers can both expose `read_file` without collision).
 */
final class McpTool(manager: McpManager,
                    serverConfig: McpServerConfig,
                    definition: McpToolDefinition) extends Tool {
  type Input  = JsonInput
  type Output = TextToolOutput
  val inputRW  = summon[RW[JsonInput]]
  val outputRW = summon[RW[TextToolOutput]]

  override val name: ToolName = ToolName(serverConfig.prefix.getOrElse("") + definition.name)
  override val description: String = definition.description.getOrElse("")

  override def inputDefinition: Definition =
    JsonSchemaToDefinition(definition.inputSchema)

  override def kind: sigil.tool.ToolKind = McpKind
  override val modes: Set[Id[Mode]] = Set.empty
  override val space: SpaceId = serverConfig.space
  override val keywords: Set[String] = Set("mcp", serverConfig.name)
  override val examples: List[ToolExample] = Nil
  override val createdBy: Option[ParticipantId] = None
  override val _id: Id[Tool] = Id(name.value)
  override val created: Timestamp = Tool.Epoch
  override val modified: Timestamp = Tool.Epoch

  override def executeResult(input: JsonInput, context: TurnContext): Task[ToolResult[TextToolOutput]] = {
    val agentId = context.chain.collectFirst { case a: AgentParticipantId => a }
      .getOrElse(context.caller)
    manager.callTool(serverConfig.name, definition.name, input.json, agentId).map { result =>
      val isError = result.get("isError").exists(_.asBoolean)
      val rendered = renderResult(result)
      if (isError) ToolResult.failure(if (rendered.isEmpty) "(empty error)" else rendered)
      else ToolResult.Success(TextToolOutput(rendered))
    }.handleError { t =>
      Task.pure(ToolResult.failure(s"MCP tool error: ${t.getMessage}"))
    }
  }

  /**
   * Render an MCP `CallToolResult` (`{ content: [...], isError }`)
   * into a single text string. Each content block is rendered to
   * text; text blocks pass through, other blocks serialise to JSON.
   */
  private def renderResult(result: Json): String = {
    val blocks = result.get("content").map(_.asVector.toList).getOrElse(Nil)
    blocks.flatMap(blockToText).mkString("\n")
  }

  private def blockToText(block: Json): Option[String] = {
    val t = block.get("type").map(_.asString).getOrElse("")
    t match {
      case "text" => block.get("text").map(_.asString)
      case _      => Some(fabric.io.JsonFormatter.Compact(block))
    }
  }
}
