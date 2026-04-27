package sigil.mcp

import fabric.rw.*
import rapid.{Stream, Task}
import sigil.TurnContext
import sigil.event.{Event, Message, MessageVisibility, MessageRole}
import sigil.signal.EventState
import sigil.tool.{ToolInput, ToolName, TypedTool}
import sigil.tool.model.ResponseContent

case class RefreshMcpServerInput(name: String) extends ToolInput derives RW

/** Force-refresh the cached tool / resource / prompt list for a server. */
final class RefreshMcpServerTool(manager: McpManager) extends TypedTool[RefreshMcpServerInput](
  name = ToolName("refresh_mcp_server"),
  description = "Force-refresh the cached tool / resource / prompt list for a registered MCP server, bypassing the standard refresh interval."
) {
  override protected def executeTyped(input: RefreshMcpServerInput, context: TurnContext): Stream[Event] =
    Stream.force(manager.refresh(input.name).map { tools =>
      Stream.emit[Event](Message(
        participantId = context.caller,
        conversationId = context.conversation.id,
        topicId = context.conversation.currentTopicId,
        content = Vector(ResponseContent.Text(s"Refreshed '${input.name}' — ${tools.size} tools.")),
        state = EventState.Complete,
        role = MessageRole.Tool,
        visibility = MessageVisibility.All
      ))
    }.handleError { e =>
      Task.pure(Stream.emit[Event](Message(
        participantId = context.caller,
        conversationId = context.conversation.id,
        topicId = context.conversation.currentTopicId,
        content = Vector(ResponseContent.Text(s"Refresh failed for '${input.name}': ${e.getMessage}")),
        state = EventState.Complete,
        role = MessageRole.Tool,
        visibility = MessageVisibility.All
      )))
    })
}
