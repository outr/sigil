package sigil.mcp

import fabric.*
import fabric.rw.*
import rapid.Task

import java.io.{BufferedReader, InputStreamReader}
import java.nio.charset.StandardCharsets

/**
 * [[McpClient]] backed by a child process spoken to via newline-
 * delimited JSON-RPC on stdin/stdout. The 2024-11-05 spec's primary
 * transport.
 *
 * Process lifecycle is managed by [[McpManager]] — this class just
 * spawns the subprocess on [[start]] and tears it down on [[close]].
 * If the subprocess exits unexpectedly the reader fiber surfaces it
 * via failed pending requests; apps reconnect by getting a fresh
 * client from the manager.
 */
final class StdioMcpClient(override val config: McpServerConfig,
                           samplingHandler: SamplingHandler,
                           notificationListener: (String, Json) => Task[Unit] = (_, _) => Task.unit)
  extends McpClient {

  private var process: Option[Process] = None
  private var rpc: Option[McpJsonRpc]  = None

  private val stdio: McpTransport.Stdio = config.transport match {
    case s: McpTransport.Stdio => s
    case other => sys.error(s"StdioMcpClient given non-stdio transport: $other")
  }

  override def start(): Task[Unit] = Task.defer {
    val pb = new ProcessBuilder((stdio.command :: stdio.args)*)
    pb.redirectErrorStream(false)
    val proc   = pb.start()
    val reader = new BufferedReader(new InputStreamReader(proc.getInputStream, StandardCharsets.UTF_8))
    val writer = proc.getOutputStream
    process = Some(proc)

    val pump = new McpJsonRpc(
      input               = reader,
      output              = writer,
      requestHandler      = handleServerRequest,
      notificationHandler = notificationListener
    )
    rpc = Some(pump)

    pump.start().flatMap(_ => initialize())
  }

  override def close(): Task[Unit] = Task.defer {
    val closeRpc = rpc.map(_.close()).getOrElse(Task.unit)
    closeRpc.map { _ =>
      process.foreach(_.destroyForcibly())
      process = None
      rpc = None
    }
  }

  override def listTools(): Task[List[McpToolDefinition]] =
    callRpc("tools/list").map { result =>
      result.get("tools").map(_.asVector.toList.map { entry =>
        McpToolDefinition(
          name = entry.get("name").map(_.asString).getOrElse(""),
          description = entry.get("description").map(_.asString),
          inputSchema = entry.get("inputSchema").getOrElse(Obj.empty)
        )
      }).getOrElse(Nil)
    }

  override def callTool(name: String, arguments: Json, onWireId: Long => Unit = _ => ()): Task[Json] =
    Task.defer(rpcOrFail.requestWithId("tools/call", obj("name" -> str(name), "arguments" -> arguments), onWireId))

  override def listResources(): Task[List[McpResource]] =
    callRpc("resources/list").map { result =>
      result.get("resources").map(_.asVector.toList.map { entry =>
        McpResource(
          uri = entry.get("uri").map(_.asString).getOrElse(""),
          name = entry.get("name").map(_.asString),
          description = entry.get("description").map(_.asString),
          mimeType = entry.get("mimeType").map(_.asString)
        )
      }).getOrElse(Nil)
    }

  override def readResource(uri: String): Task[Json] =
    callRpc("resources/read", obj("uri" -> str(uri)))

  override def listPrompts(): Task[List[McpPrompt]] =
    callRpc("prompts/list").map { result =>
      result.get("prompts").map(_.asVector.toList.map { entry =>
        val args = entry.get("arguments").map(_.asVector.toList.map { a =>
          McpPromptArgument(
            name = a.get("name").map(_.asString).getOrElse(""),
            description = a.get("description").map(_.asString),
            required = a.get("required").map(_.asBoolean).getOrElse(false)
          )
        }).getOrElse(Nil)
        McpPrompt(
          name = entry.get("name").map(_.asString).getOrElse(""),
          description = entry.get("description").map(_.asString),
          arguments = args
        )
      }).getOrElse(Nil)
    }

  override def getPrompt(name: String, arguments: Map[String, String] = Map.empty): Task[Json] = {
    val args = Obj(arguments.map { case (k, v) => k -> str(v) })
    callRpc("prompts/get", obj("name" -> str(name), "arguments" -> args))
  }

  override def cancelRequest(requestId: Long, reason: Option[String] = None): Task[Unit] = Task.defer {
    rpc match {
      case Some(p) =>
        val params = reason match {
          case Some(r) => obj("requestId" -> num(requestId), "reason" -> str(r))
          case None    => obj("requestId" -> num(requestId))
        }
        p.notify("notifications/cancelled", params)
      case None => Task.unit
    }
  }

  private def initialize(): Task[Unit] =
    callRpc("initialize", obj(
      "protocolVersion" -> str("2024-11-05"),
      "capabilities"    -> obj("sampling" -> obj()),
      "clientInfo"      -> obj("name" -> str("sigil"), "version" -> str("1.0.0"))
    )).flatMap(_ => rpcOrFail.notify("notifications/initialized"))

  private def rpcOrFail: McpJsonRpc =
    rpc.getOrElse(sys.error(s"MCP client '${config.name}' is not connected"))

  private def callRpc(method: String, params: Json = Obj.empty): Task[Json] =
    Task.defer(rpcOrFail.request(method, params))

  private def handleServerRequest(method: String, params: Json): Task[Json] = method match {
    case "sampling/createMessage" => samplingHandler.handle(config.name, params)
    case "roots/list"             => Task.pure(obj("roots" -> Arr(config.roots.map(p => obj("uri" -> str(s"file://$p"))).toVector)))
    case other                    => Task.error(new McpError(-32601, s"Unhandled server request: $other"))
  }
}
