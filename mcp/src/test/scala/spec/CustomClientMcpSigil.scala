package spec

import fabric.{Json, obj, str}
import sigil.mcp.{McpClient, McpClientContext, McpToolDefinition}

import java.util.concurrent.ConcurrentHashMap

/**
 * Test host wiring an app-supplied [[McpClient]]: configs marked with
 * `metadata("transport") == "in-process"` route to an
 * [[InProcessMcpClient]]; everything else falls through to the
 * framework's built-in transports.
 */
object CustomClientMcpSigil extends TestMcpSigilBase {
  val Marker: String = "in-process"

  val cannedTools: List[McpToolDefinition] = List(
    McpToolDefinition(
      name = "tunnelled_echo",
      description = Some("Echo a value back through the app's own transport."),
      inputSchema = obj("type" -> str("object"))
    )
  )

  private val supplied = new ConcurrentHashMap[String, InProcessMcpClient]()

  /** The client the host handed the manager for `name`, if any. */
  def suppliedClient(name: String): Option[InProcessMcpClient] = Option(supplied.get(name))

  override protected def mcpClientFor(context: McpClientContext): Option[McpClient] =
    context.config.metadata.get("transport").filter(_ == Marker).map { _ =>
      supplied.computeIfAbsent(
        context.config.name,
        _ => new InProcessMcpClient(context.config, cannedTools, context.notificationListener)
      )
    }
}
