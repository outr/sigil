package sigil.mcp

import fabric.rw.*
import rapid.Task
import sigil.TurnContext
import sigil.tool.{TextToolOutput, Tool, ToolExample, ToolInput, ToolName, ToolResult}

case class AddMcpServerInput(name: String,
                             command: Option[String] = None,
                             args: List[String] = Nil,
                             url: Option[String] = None,
                             prefix: Option[String] = None,
                             headers: Map[String, String] = Map.empty,
                             roots: List[String] = Nil) extends ToolInput derives RW

/**
 * Register an MCP server. `command` (with optional `args`) selects
 * stdio transport; `url` (with optional `headers`) selects HTTP+SSE
 * transport. Persisted via [[McpManager.addConfig]] so the server
 * is available across restarts; first call lazily connects.
 */
final class AddMcpServerTool(manager: McpManager) extends Tool {
  type Input  = AddMcpServerInput
  type Output = TextToolOutput
  val inputRW  = summon[RW[AddMcpServerInput]]
  val outputRW = summon[RW[TextToolOutput]]

  val name = ToolName("add_mcp_server")
  val description =
    """Register an MCP (Model Context Protocol) server.
      |
      |Use either `command` (+ optional `args`) for stdio transport, or `url` (+ optional `headers`) for HTTP+SSE.
      |`prefix` (optional) is prepended to every tool name advertised by this server, disambiguating cross-server collisions.
      |`roots` (optional) lists filesystem workspace roots to advertise to filesystem-aware servers.
      |
      |Persists the config; the server is available across restarts and connects lazily on first use.""".stripMargin
  override val examples = List(
    ToolExample(
      "stdio fetch server",
      AddMcpServerInput(name = "fetch", command = Some("mcp-server-fetch"), prefix = Some("fetch_"))
    ),
    ToolExample(
      "remote HTTP+SSE server with auth",
      AddMcpServerInput(name = "github", url = Some("https://mcp.example.com"), headers = Map("Authorization" -> "Bearer ..."))
    )
  )

  import spice.net.{TLDValidation, URL}

  override def executeResult(input: AddMcpServerInput, context: TurnContext): Task[ToolResult[TextToolOutput]] = {
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
      case Left(msg) => Task.pure(ToolResult.failure(msg))
      case Right(t) =>
        val cfg = McpServerConfig(
          name = input.name,
          transport = t,
          prefix = input.prefix,
          roots = input.roots
        )
        manager.addConfig(cfg).map { stored =>
          ToolResult.Success(TextToolOutput(s"MCP server '${stored.name}' registered."))
        }.handleError { e =>
          Task.pure(ToolResult.failure(s"Failed to register: ${e.getMessage}"))
        }
    }
  }
}
