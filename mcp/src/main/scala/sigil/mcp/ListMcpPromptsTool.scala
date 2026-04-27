package sigil.mcp

import fabric.rw.*
import rapid.{Stream, Task}
import sigil.TurnContext
import sigil.event.{Event, Message, MessageVisibility, Role}
import sigil.signal.EventState
import sigil.tool.{ToolInput, ToolName, TypedTool}
import sigil.tool.model.ResponseContent

case class ListMcpPromptsInput(server: String) extends ToolInput derives RW

/** List the prompt templates advertised by a registered MCP server. */
final class ListMcpPromptsTool(manager: McpManager) extends TypedTool[ListMcpPromptsInput](
  name = ToolName("list_mcp_prompts"),
  description = "List the prompt templates advertised by a registered MCP server, including their argument names."
) {
  override protected def executeTyped(input: ListMcpPromptsInput, context: TurnContext): Stream[Event] =
    Stream.force(manager.listPrompts(input.server).map { prompts =>
      val text = if (prompts.isEmpty) "(no prompts advertised)"
      else prompts.map { p =>
        val args = if (p.arguments.isEmpty) "" else p.arguments.map { a =>
          val req = if (a.required) "*" else ""
          s"${a.name}$req"
        }.mkString("(", ", ", ")")
        val desc = p.description.map(d => s" — $d").getOrElse("")
        s"- ${p.name}$args$desc"
      }.mkString("\n")
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
        content = Vector(ResponseContent.Text(s"List prompts failed: ${e.getMessage}")),
        state = EventState.Complete,
        role = Role.Tool,
        visibility = MessageVisibility.All
      )))
    })
}
