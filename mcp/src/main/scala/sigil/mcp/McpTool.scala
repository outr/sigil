package sigil.mcp

import fabric.{Json, Obj}
import fabric.define.Definition
import fabric.rw.*
import lightdb.id.Id
import lightdb.time.Timestamp
import rapid.Stream
import sigil.{SpaceId, TurnContext}
import sigil.event.{Event, Message, MessageVisibility, Role}
import sigil.participant.{AgentParticipantId, ParticipantId}
import sigil.provider.{ConversationMode, Mode}
import sigil.signal.EventState
import sigil.tool.{Tool, ToolExample, ToolInput, ToolName, ToolSchema}
import sigil.tool.model.ResponseContent

/**
 * Sigil [[Tool]] backed by an MCP-server-side tool. The wire schema
 * advertised by the server is surfaced directly to the LLM via
 * `inputDefinition` — Sigil generates none of its own. The LLM's
 * arguments arrive as fabric `Json` (wrapped in [[JsonInput]]); the
 * call body fans them out via [[McpManager.callTool]] and translates
 * the server's `CallToolResult` into one or more `Message` events.
 *
 * The display name in `name` includes the server's `prefix` (so two
 * servers can both expose `read_file` without collision).
 */
final class McpTool(manager: McpManager,
                    serverConfig: McpServerConfig,
                    definition: McpToolDefinition) extends Tool {

  override val name: ToolName = ToolName(serverConfig.prefix + definition.name)
  override val description: String = definition.description.getOrElse("")
  override val inputRW: RW[JsonInput] = summon[RW[JsonInput]]

  override def inputDefinition: Definition =
    JsonSchemaToDefinition(definition.inputSchema)

  override val modes: Set[Id[Mode]] = Set(ConversationMode.id)
  override val spaces: Set[SpaceId] = serverConfig.space.toSet
  override val keywords: Set[String] = Set("mcp", serverConfig.name)
  override val examples: List[ToolExample] = Nil
  override val createdBy: Option[ParticipantId] = None
  override val _id: Id[Tool] = Id(name.value)
  override val created: Timestamp = Tool.Epoch
  override val modified: Timestamp = Tool.Epoch

  override def execute(input: ToolInput, context: TurnContext): Stream[Event] = {
    val args = input match {
      case j: JsonInput => j.json
      case other => summon[RW[ToolInput]].read(other)
    }
    val agentId = context.chain.collectFirst { case a: AgentParticipantId => a }
      .getOrElse(context.caller)
    Stream.force(
      manager.callTool(serverConfig.name, definition.name, args, agentId).map { result =>
        Stream.emits(translate(result, context))
      }.handleError { t =>
        rapid.Task {
          Stream.emit[Event](errorMessage(t.getMessage, context))
        }
      }
    )
  }

  /**
   * Translate an MCP `CallToolResult` (`{ content: [...], isError }`)
   * into Sigil events. Each content block becomes its own Message
   * carrying the appropriate ResponseContent. Errors emit a single
   * Message with the error string.
   */
  private def translate(result: Json, context: TurnContext): List[Event] = {
    val isError = result.get("isError").exists(_.asBoolean)
    val blocks = result.get("content").map(_.asVector.toList).getOrElse(Nil)
    val rendered = blocks.flatMap(blockToContent)
    val content = if (rendered.isEmpty) Vector(ResponseContent.Text(if (isError) "(empty error)" else "")) else rendered.toVector
    List(Message(
      participantId = context.caller,
      conversationId = context.conversation.id,
      topicId = context.conversation.currentTopicId,
      content = content,
      state = EventState.Complete,
      role = Role.Tool,
      visibility = MessageVisibility.All
    ))
  }

  private def blockToContent(block: Json): Option[ResponseContent] = {
    val t = block.get("type").map(_.asString).getOrElse("")
    t match {
      case "text" => block.get("text").map(j => ResponseContent.Text(j.asString))
      case _ =>
        val asJson = fabric.io.JsonFormatter.Compact(block)
        Some(ResponseContent.Text(asJson))
    }
  }

  private def errorMessage(msg: String, context: TurnContext): Message =
    Message(
      participantId = context.caller,
      conversationId = context.conversation.id,
      topicId = context.conversation.currentTopicId,
      content = Vector(ResponseContent.Text(s"MCP tool error: $msg")),
      state = EventState.Complete,
      role = Role.Tool
    )
}
