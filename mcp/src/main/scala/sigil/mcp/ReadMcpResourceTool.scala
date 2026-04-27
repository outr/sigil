package sigil.mcp

import fabric.rw.*
import fabric.io.JsonFormatter
import rapid.{Stream, Task}
import sigil.TurnContext
import sigil.event.{Event, Message, MessageVisibility, Role}
import sigil.signal.EventState
import sigil.tool.{ToolInput, ToolName, TypedTool}
import sigil.tool.model.ResponseContent

case class ReadMcpResourceInput(server: String, uri: String) extends ToolInput derives RW

/** Read a resource from a registered MCP server by URI. */
final class ReadMcpResourceTool(manager: McpManager) extends TypedTool[ReadMcpResourceInput](
  name = ToolName("read_mcp_resource"),
  description =
    """Fetch the contents of a resource from a registered MCP server. The server identifies the resource by URI;
      |use list_mcp_servers + (server-specific) discovery to learn what URIs are available.""".stripMargin
) {
  override protected def executeTyped(input: ReadMcpResourceInput, context: TurnContext): Stream[Event] =
    Stream.force(manager.readResource(input.server, input.uri).map { result =>
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
        content = Vector(ResponseContent.Text(s"Read resource failed: ${e.getMessage}")),
        state = EventState.Complete,
        role = Role.Tool,
        visibility = MessageVisibility.All
      )))
    })
}
