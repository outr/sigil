package sigil.mcp

import fabric.*
import fabric.io.{JsonFormatter, JsonParser}
import rapid.Task
import spice.http.HeaderKey
import spice.http.client.HttpClient

import java.util.concurrent.atomic.AtomicBoolean

/**
 * [[McpClient]] backed by HTTP+SSE per the MCP 2024-11-05 spec.
 *
 * Two channels:
 *   - **Outgoing requests / notifications** — POST JSON to the
 *     endpoint. The server may answer inline (Content-Type:
 *     application/json) or via the listen channel.
 *   - **Server-initiated messages** — a long-lived SSE GET on the
 *     same endpoint streams `data:` events; each event is a
 *     JSON-RPC message dispatched through the shared
 *     [[McpDispatcher]] (server requests like `sampling/createMessage`,
 *     notifications like `notifications/cancelled`, and asynchronous
 *     responses to outgoing requests).
 *
 * The `Mcp-Session-Id` header is captured from the first response
 * and reused on every subsequent request and on the SSE GET.
 */
final class HttpSseMcpClient(override val config: McpServerConfig,
                             samplingHandler: SamplingHandler,
                             notificationListener: (String, Json) => Task[Unit] = (_, _) => Task.unit)
  extends McpClient {

  private val httpSse: McpTransport.HttpSse = config.transport match {
    case h: McpTransport.HttpSse => h
    case other => sys.error(s"HttpSseMcpClient given non-HttpSse transport: $other")
  }

  private val closed = new AtomicBoolean(false)
  @volatile private var sessionId: Option[String] = None

  private val dispatcher = new McpDispatcher(
    send                = sendOutgoing,
    requestHandler      = (method, params) => method match {
      case "sampling/createMessage" => samplingHandler.handle(config.name, params)
      case "roots/list"             => Task.pure(obj("roots" -> Arr(config.roots.map(p => obj("uri" -> str(s"file://$p"))).toVector)))
      case other                    => Task.error(new McpError(-32601, s"Unhandled server request: $other"))
    },
    notificationHandler = notificationListener
  )

  override def start(): Task[Unit] = Task.defer {
    // Initialize first (gives us the session id), then open the SSE listen channel.
    dispatcher.request("initialize", obj(
      "protocolVersion" -> str("2024-11-05"),
      "capabilities"    -> obj("sampling" -> obj()),
      "clientInfo"      -> obj("name" -> str("sigil"), "version" -> str("1.0.0"))
    ), _ => ()).flatMap(_ => dispatcher.notify("notifications/initialized", Obj.empty))
      .flatMap(_ => startSseListener())
  }

  override def close(): Task[Unit] = Task {
    if (closed.compareAndSet(false, true)) {
      dispatcher.failPending(new McpError(-1, "Connection closed"))
    }
  }

  override def listTools(): Task[List[McpToolDefinition]] =
    dispatcher.request("tools/list", Obj.empty, _ => ()).map { result =>
      result.get("tools").map(_.asVector.toList.map { entry =>
        McpToolDefinition(
          name = entry.get("name").map(_.asString).getOrElse(""),
          description = entry.get("description").map(_.asString),
          inputSchema = entry.get("inputSchema").getOrElse(Obj.empty)
        )
      }).getOrElse(Nil)
    }

  override def callTool(name: String, arguments: Json, onWireId: Long => Unit = _ => ()): Task[Json] =
    dispatcher.request("tools/call", obj("name" -> str(name), "arguments" -> arguments), onWireId)

  override def listResources(): Task[List[McpResource]] =
    dispatcher.request("resources/list", Obj.empty, _ => ()).map { result =>
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
    dispatcher.request("resources/read", obj("uri" -> str(uri)), _ => ())

  override def listPrompts(): Task[List[McpPrompt]] =
    dispatcher.request("prompts/list", Obj.empty, _ => ()).map { result =>
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
    dispatcher.request("prompts/get", obj("name" -> str(name), "arguments" -> args), _ => ())
  }

  override def cancelRequest(requestId: Long, reason: Option[String] = None): Task[Unit] = {
    val params = reason match {
      case Some(r) => obj("requestId" -> num(requestId), "reason" -> str(r))
      case None    => obj("requestId" -> num(requestId))
    }
    dispatcher.notify("notifications/cancelled", params)
  }

  /** Outgoing-message hook used by the dispatcher: POST a JSON-RPC
    * envelope to the endpoint. Inline JSON responses are dispatched
    * back through the same [[McpDispatcher]] so request correlation
    * and notifications dispatch identically whether the wire chose
    * inline or SSE delivery. */
  private def sendOutgoing(message: Json): Unit = {
    if (closed.get()) return
    postJson(message).map { responseOpt =>
      responseOpt.foreach(dispatcher.dispatchIncoming)
    }.handleError(t => Task { scribe.warn(s"MCP HTTP send failed: ${t.getMessage}") }).startUnit()
  }

  private def postJson(payload: Json): Task[Option[Json]] = {
    val withHeaders = httpSse.headers.foldLeft(
      HttpClient.url(httpSse.url).post
        .header("Accept", "application/json, text/event-stream")
        .json(payload)
    ) { case (client, (k, v)) => client.header(k, v) }
    val withSession = sessionId.fold(withHeaders)(id => withHeaders.header("Mcp-Session-Id", id))
    withSession.send().flatMap { response =>
      response.headers.first(HeaderKey("Mcp-Session-Id")).foreach { sid => sessionId = Some(sid) }
      response.content match {
        case Some(c) => c.asString.map { s =>
          if (s.isEmpty) None else Some(JsonParser(s))
        }
        case None => Task.pure(None)
      }
    }
  }

  /** Open a long-lived SSE GET on the endpoint. Each `data:` event
    * is parsed as a JSON-RPC message and pushed into the
    * dispatcher. Stops naturally when [[close]] is called or the
    * server closes the stream. */
  private def startSseListener(): Task[Unit] = Task {
    Task.defer {
      val withHeaders = httpSse.headers.foldLeft(
        HttpClient.url(httpSse.url).get.header("Accept", "text/event-stream")
      ) { case (client, (k, v)) => client.header(k, v) }
      val withSession = sessionId.fold(withHeaders)(id => withHeaders.header("Mcp-Session-Id", id))
      withSession.streamLines().flatMap { stream =>
        stream
          .takeWhile(_ => !closed.get())
          .evalMap { line =>
            Task {
              if (line.startsWith("data:")) {
                val payload = line.stripPrefix("data:").trim
                if (payload.nonEmpty) {
                  try dispatcher.dispatchIncoming(JsonParser(payload))
                  catch { case t: Throwable => scribe.warn(s"MCP SSE: dispatch failed: ${t.getMessage}") }
                }
              }
              ()
            }
          }
          .drain
      }
    }.handleError(t => Task { scribe.warn(s"MCP SSE listener exited: ${t.getMessage}") }).startUnit()
    ()
  }
}
