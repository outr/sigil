package sigil.mcp

import fabric.*
import fabric.io.JsonParser
import rapid.Task
import spice.http.client.HttpClient

import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger}

/**
 * [[McpClient]] backed by an HTTP+SSE transport. Each outgoing
 * request POSTs JSON to the configured URL; responses arrive inline
 * as a single JSON-RPC reply (this implementation does not consume
 * a long-lived SSE stream — apps that need server-initiated
 * notifications over HTTP should compose a separate SSE consumer
 * via spice's streaming API and dispatch through the
 * `notificationListener`/`samplingHandler` callbacks).
 *
 * `Mcp-Session-Id` from the first response is reused on subsequent
 * requests, per the 2024-11-05 spec.
 *
 * For the full bidirectional flow (server-initiated requests over
 * SSE), most current MCP servers ship as stdio binaries — that
 * transport ([[StdioMcpClient]]) handles bidirectionality natively.
 */
final class HttpSseMcpClient(override val config: McpServerConfig,
                             samplingHandler: SamplingHandler,
                             notificationListener: (String, Json) => Task[Unit] = (_, _) => Task.unit)
  extends McpClient {

  private val httpSse: McpTransport.HttpSse = config.transport match {
    case h: McpTransport.HttpSse => h
    case other => sys.error(s"HttpSseMcpClient given non-HttpSse transport: $other")
  }

  private val nextId = new AtomicInteger(1)
  private val closed = new AtomicBoolean(false)
  @volatile private var sessionId: Option[String] = None

  override def start(): Task[Unit] =
    request("initialize", obj(
      "protocolVersion" -> str("2024-11-05"),
      "capabilities"    -> obj("sampling" -> obj()),
      "clientInfo"      -> obj("name" -> str("sigil"), "version" -> str("1.0.0"))
    )).flatMap(_ => notify("notifications/initialized"))

  override def close(): Task[Unit] = Task {
    closed.set(true)
  }

  override def listTools(): Task[List[McpToolDefinition]] =
    request("tools/list").map { result =>
      result.get("tools").map(_.asVector.toList.map { entry =>
        McpToolDefinition(
          name = entry.get("name").map(_.asString).getOrElse(""),
          description = entry.get("description").map(_.asString),
          inputSchema = entry.get("inputSchema").getOrElse(Obj.empty)
        )
      }).getOrElse(Nil)
    }

  override def callTool(name: String, arguments: Json, onWireId: Long => Unit = _ => ()): Task[Json] = {
    val id = nextId.get().toLong
    onWireId(id)
    request("tools/call", obj("name" -> str(name), "arguments" -> arguments))
  }

  override def listResources(): Task[List[McpResource]] =
    request("resources/list").map { result =>
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
    request("resources/read", obj("uri" -> str(uri)))

  override def listPrompts(): Task[List[McpPrompt]] =
    request("prompts/list").map { result =>
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
    request("prompts/get", obj("name" -> str(name), "arguments" -> args))
  }

  override def cancelRequest(requestId: Long, reason: Option[String] = None): Task[Unit] = {
    val params = reason match {
      case Some(r) => obj("requestId" -> num(requestId), "reason" -> str(r))
      case None    => obj("requestId" -> num(requestId))
    }
    notify("notifications/cancelled", params)
  }

  private def request(method: String, params: Json = Obj.empty): Task[Json] = Task.defer {
    if (closed.get()) Task.error(new McpError(-1, "MCP connection closed"))
    else {
      val id = nextId.getAndIncrement()
      val payload = obj(
        "jsonrpc" -> str("2.0"),
        "id"      -> num(id),
        "method"  -> str(method),
        "params"  -> params
      )
      sendHttp(payload).map(parseRpcResponse)
    }
  }

  private def notify(method: String, params: Json = Obj.empty): Task[Unit] = Task.defer {
    if (closed.get()) Task.unit
    else {
      val payload = obj(
        "jsonrpc" -> str("2.0"),
        "method"  -> str(method),
        "params"  -> params
      )
      sendHttp(payload).map(_ => ())
    }
  }

  private def sendHttp(payload: Json): Task[Json] = {
    val withHeaders = httpSse.headers.foldLeft(
      HttpClient.url(httpSse.url).post
        .header("Accept", "application/json, text/event-stream")
        .json(payload)
    ) { case (client, (k, v)) => client.header(k, v) }
    val withSession = sessionId.fold(withHeaders)(id => withHeaders.header("Mcp-Session-Id", id))
    withSession.send().flatMap { response =>
      response.headers.first(spice.http.HeaderKey("Mcp-Session-Id")).foreach { sid => sessionId = Some(sid) }
      response.content match {
        case Some(c) => c.asString.map { s =>
          if (s.isEmpty) Null else JsonParser(s)
        }
        case None => Task.pure(Null)
      }
    }
  }

  private def parseRpcResponse(json: Json): Json = {
    json.get("error") match {
      case Some(err) if err != Null =>
        val code    = err.get("code").map(_.asInt).getOrElse(-1)
        val message = err.get("message").map(_.asString).getOrElse("Unknown MCP error")
        throw new McpError(code, message)
      case _ =>
        json.get("result").getOrElse(Null)
    }
  }
}
