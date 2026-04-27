package sigil.mcp

import fabric.rw.*
import rapid.{Stream, Task}
import sigil.TurnContext
import sigil.event.{Event, Message, MessageVisibility, MessageRole}
import sigil.signal.EventState
import sigil.tool.{ToolInput, ToolName, TypedTool}
import sigil.tool.model.ResponseContent

case class ListMcpServersInput() extends ToolInput derives RW

/** Return the names + transport summary of every registered MCP server. */
final class ListMcpServersTool(manager: McpManager) extends TypedTool[ListMcpServersInput](
  name = ToolName("list_mcp_servers"),
  description = "List the registered MCP servers — name, transport, prefix, and the count of tools each currently exposes."
) {
  override protected def executeTyped(input: ListMcpServersInput, context: TurnContext): Stream[Event] =
    Stream.force(manager.listConfigs().flatMap { configs =>
      Task.sequence(configs.map { cfg =>
        manager.listTools(cfg.name).map(_.size).handleError(_ => Task.pure(-1)).map { count =>
          val transport = cfg.transport match {
            case McpTransport.Stdio(cmd, args) => s"stdio: $cmd ${args.mkString(" ")}".trim
            case McpTransport.HttpSse(url, _)  => s"http: $url"
          }
          val countStr = if (count >= 0) s"$count tools" else "(unreachable)"
          s"- ${cfg.name} (${cfg.prefix}*) — $transport — $countStr"
        }
      }).map { lines =>
        val text = if (lines.isEmpty) "(no MCP servers registered)" else lines.mkString("\n")
        Stream.emit[Event](Message(
          participantId = context.caller,
          conversationId = context.conversation.id,
          topicId = context.conversation.currentTopicId,
          content = Vector(ResponseContent.Text(text)),
          state = EventState.Complete,
          role = MessageRole.Tool,
          visibility = MessageVisibility.All
        ))
      }
    })
}
