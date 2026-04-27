package sigil.mcp

import fabric.rw.*
import rapid.{Stream, Task}
import sigil.TurnContext
import sigil.event.{Event, Message, MessageVisibility, MessageRole}
import sigil.signal.EventState
import sigil.tool.{ToolExample, ToolInput, ToolName, TypedTool}
import sigil.tool.model.ResponseContent

case class AddMcpServerInput(name: String,
                             command: Option[String] = None,
                             args: List[String] = Nil,
                             url: Option[String] = None,
                             prefix: String = "",
                             headers: Map[String, String] = Map.empty,
                             roots: List[String] = Nil) extends ToolInput derives RW

/**
 * Register an MCP server. `command` (with optional `args`) selects
 * stdio transport; `url` (with optional `headers`) selects HTTP+SSE
 * transport. Persisted via [[McpManager.addConfig]] so the server
 * is available across restarts; first call lazily connects.
 */
final class AddMcpServerTool(manager: McpManager) extends TypedTool[AddMcpServerInput](
  name = ToolName("add_mcp_server"),
  description =
    """Register an MCP (Model Context Protocol) server.
      |
      |Use either `command` (+ optional `args`) for stdio transport, or `url` (+ optional `headers`) for HTTP+SSE.
      |`prefix` (optional) is prepended to every tool name advertised by this server, disambiguating cross-server collisions.
      |`roots` (optional) lists filesystem workspace roots to advertise to filesystem-aware servers.
      |
      |Persists the config; the server is available across restarts and connects lazily on first use.""".stripMargin,
  examples = List(
    ToolExample(
      "stdio fetch server",
      AddMcpServerInput(name = "fetch", command = Some("mcp-server-fetch"), prefix = "fetch_")
    ),
    ToolExample(
      "remote HTTP+SSE server with auth",
      AddMcpServerInput(name = "github", url = Some("https://mcp.example.com"), headers = Map("Authorization" -> "Bearer ..."))
    )
  )
) {
  import spice.net.{TLDValidation, URL}

  override protected def executeTyped(input: AddMcpServerInput, context: TurnContext): Stream[Event] = {
    val transport = (input.command, input.url) match {
      case (Some(cmd), _) => Right(McpTransport.Stdio(cmd, input.args))
      case (_, Some(urlStr)) =>
        URL.get(urlStr, tldValidation = TLDValidation.Off) match {
          case Right(u) => Right(McpTransport.HttpSse(u, input.headers))
          case Left(e)  => Left(s"Invalid url '$urlStr': $e")
        }
      case _ => Left("Either `command` or `url` must be provided.")
    }
    transport match {
      case Left(msg) => Stream.emit(reply(context, msg, isError = true))
      case Right(t) =>
        val cfg = McpServerConfig(
          name = input.name,
          transport = t,
          prefix = input.prefix,
          roots = input.roots
        )
        Stream.force(manager.addConfig(cfg).map { stored =>
          Stream.emit(reply(context, s"MCP server '${stored.name}' registered.", isError = false))
        }.handleError { e =>
          Task.pure(Stream.emit(reply(context, s"Failed to register: ${e.getMessage}", isError = true)))
        })
    }
  }

  private def reply(context: TurnContext, text: String, isError: Boolean): Event =
    Message(
      participantId = context.caller,
      conversationId = context.conversation.id,
      topicId = context.conversation.currentTopicId,
      content = Vector(ResponseContent.Text(text)),
      state = EventState.Complete,
      role = MessageRole.Tool,
      visibility = MessageVisibility.All
    )
}
