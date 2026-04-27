package sigil.mcp

import fabric.rw.*
import rapid.{Stream, Task}
import sigil.TurnContext
import sigil.event.{Event, Message, MessageVisibility, Role}
import sigil.signal.EventState
import sigil.tool.{ToolInput, ToolName, TypedTool}
import sigil.tool.model.ResponseContent

case class TestMcpServerInput(name: String) extends ToolInput derives RW

/** Force a connection attempt and report success or failure. */
final class TestMcpServerTool(manager: McpManager) extends TypedTool[TestMcpServerInput](
  name = ToolName("test_mcp_server"),
  description = "Connect to a registered MCP server (or use the cached connection if active) and report success or failure."
) {
  override protected def executeTyped(input: TestMcpServerInput, context: TurnContext): Stream[Event] =
    Stream.force(manager.test(input.name).map {
      case Right(tools) =>
        Stream.emit[Event](reply(context, s"OK — '${input.name}' connected, ${tools.size} tools."))
      case Left(t) =>
        Stream.emit[Event](reply(context, s"FAIL — '${input.name}': ${t.getMessage}"))
    })

  private def reply(context: TurnContext, text: String): Event =
    Message(
      participantId = context.caller,
      conversationId = context.conversation.id,
      topicId = context.conversation.currentTopicId,
      content = Vector(ResponseContent.Text(text)),
      state = EventState.Complete,
      role = Role.Tool,
      visibility = MessageVisibility.All
    )
}
