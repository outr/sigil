package sigil.mcp

import fabric.rw.*
import fabric.io.JsonFormatter
import rapid.{Stream, Task}
import sigil.TurnContext
import sigil.event.{Event, Message, MessageVisibility, Role}
import sigil.signal.EventState
import sigil.tool.{ToolInput, ToolName, TypedTool}
import sigil.tool.model.ResponseContent

case class GetMcpPromptInput(server: String,
                             prompt: String,
                             arguments: Map[String, String] = Map.empty) extends ToolInput derives RW

/**
 * Fetch a populated prompt template from a registered MCP server.
 * The result is the raw `GetPromptResult` shape (description +
 * messages array); apps decide how to splice it into their context.
 */
final class GetMcpPromptTool(manager: McpManager) extends TypedTool[GetMcpPromptInput](
  name = ToolName("get_mcp_prompt"),
  description =
    """Fetch a populated prompt template from a registered MCP server. Provide `server`, `prompt` (template name),
      |and any `arguments` the template requires. Returns the server's GetPromptResult JSON.""".stripMargin
) {
  override protected def executeTyped(input: GetMcpPromptInput, context: TurnContext): Stream[Event] =
    Stream.force(manager.getPrompt(input.server, input.prompt, input.arguments).map { result =>
      val text = JsonFormatter.Default(result)
      Stream.emit[Event](Message(
        participantId = context.caller,
        conversationId = context.conversation.id,
        topicId = context.conversation.currentTopicId,
        content = Vector(ResponseContent.Text(text)),
        state = EventState.Complete,
        role = Role.Tool,
        visibility = MessageVisibility.All
      ))
    }.handleError { e =>
      Task.pure(Stream.emit[Event](Message(
        participantId = context.caller,
        conversationId = context.conversation.id,
        topicId = context.conversation.currentTopicId,
        content = Vector(ResponseContent.Text(s"Get prompt failed: ${e.getMessage}")),
        state = EventState.Complete,
        role = Role.Tool,
        visibility = MessageVisibility.All
      )))
    })
}
