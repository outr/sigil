package sigil.mcp

import fabric.rw.*
import rapid.{Stream, Task}
import sigil.TurnContext
import sigil.event.{Event, Message, MessageVisibility, Role}
import sigil.signal.EventState
import sigil.tool.{ToolInput, ToolName, TypedTool}
import sigil.tool.model.ResponseContent

case class RemoveMcpServerInput(name: String) extends ToolInput derives RW

/** Tear down an MCP server's persisted config and active connection. */
final class RemoveMcpServerTool(manager: McpManager) extends TypedTool[RemoveMcpServerInput](
  name = ToolName("remove_mcp_server"),
  description = "Remove a registered MCP server and disconnect any active connection. The persisted config is deleted."
) {
  override protected def executeTyped(input: RemoveMcpServerInput, context: TurnContext): Stream[Event] =
    Stream.force(manager.removeConfig(input.name).map { _ =>
      Stream.emit[Event](Message(
        participantId = context.caller,
        conversationId = context.conversation.id,
        topicId = context.conversation.currentTopicId,
        content = Vector(ResponseContent.Text(s"MCP server '${input.name}' removed.")),
        state = EventState.Complete,
        role = Role.Tool,
        visibility = MessageVisibility.All
      ))
    }.handleError { e =>
      Task.pure(Stream.emit[Event](Message(
        participantId = context.caller,
        conversationId = context.conversation.id,
        topicId = context.conversation.currentTopicId,
        content = Vector(ResponseContent.Text(s"Failed to remove '${input.name}': ${e.getMessage}")),
        state = EventState.Complete,
        role = Role.Tool,
        visibility = MessageVisibility.All
      )))
    })
}
