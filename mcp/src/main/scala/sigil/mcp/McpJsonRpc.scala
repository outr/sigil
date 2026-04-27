package sigil.mcp

import fabric.*
import fabric.io.{JsonFormatter, JsonParser}
import rapid.Task
import rapid.task.Completable

import java.io.{BufferedReader, OutputStream}
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger}

/**
 * Bidirectional JSON-RPC 2.0 message pump for MCP. Owns:
 *
 *   - **Outgoing requests** — `request(method, params)` returns a Task
 *     that completes when the server responds with the matching id.
 *   - **Outgoing notifications** — `notify(method, params)` is
 *     fire-and-forget.
 *   - **Incoming server requests** — `requestHandler` is invoked for
 *     server-initiated calls (notably `sampling/createMessage` and
 *     `roots/list`). The handler returns a `Task[Json]` whose result
 *     is written back as the response.
 *   - **Incoming notifications** — `notificationHandler` is invoked
 *     for server notifications (`notifications/cancelled`,
 *     `notifications/{tools,resources,prompts}/list_changed`,
 *     `notifications/initialized`).
 *
 * The wire format is **newline-delimited JSON** — one JSON object per
 * line, terminated by `\n`. Per the MCP 2024-11-05 stdio transport
 * spec; HTTP/SSE uses SSE event framing handled at a different layer.
 *
 * A single reader fiber drains the input stream and dispatches each
 * incoming message. Stop the pump with [[close]] — cancels the reader
 * and completes any outstanding pending requests with an error.
 */
final class McpJsonRpc(input: BufferedReader,
                       output: OutputStream,
                       requestHandler: (String, Json) => Task[Json] = (m, _) =>
                         Task.error(new McpError(-32601, s"No request handler for $m")),
                       notificationHandler: (String, Json) => Task[Unit] = (_, _) => Task.unit) {

  private val nextId    = new AtomicInteger(1)
  private val pending   = new ConcurrentHashMap[Int, Completable[Json]]()
  private val closed    = new AtomicBoolean(false)
  private val writeLock = new Object

  /** Send a request and await the matching response. */
  def request(method: String, params: Json = Obj.empty): Task[Json] =
    requestWithId(method, params, _ => ())

  /** Send a request with a callback that fires once with the wire-level
    * id (so callers can register the call for cancellation tracking).
    * The returned Task completes with the server's response. */
  def requestWithId(method: String, params: Json, onWireId: Long => Unit): Task[Json] = Task.defer {
    if (closed.get()) Task.error(new McpError(-1, "MCP connection closed"))
    else {
      val id  = nextId.getAndIncrement()
      val ref = Task.completable[Json]
      pending.put(id, ref)
      onWireId(id.toLong)
      val message = obj(
        "jsonrpc" -> str("2.0"),
        "id"      -> num(id),
        "method"  -> str(method),
        "params"  -> params
      )
      writeMessage(message)
      ref
    }
  }

  /** Send a notification — no response expected. */
  def notify(method: String, params: Json = Obj.empty): Task[Unit] = Task {
    if (!closed.get()) {
      val message = obj(
        "jsonrpc" -> str("2.0"),
        "method"  -> str(method),
        "params"  -> params
      )
      writeMessage(message)
    }
  }

  /** Start the reader fiber. Must be called once before any incoming-message dispatch. */
  def start(): Task[Unit] = Task {
    Task(readerLoop()).startUnit()
    ()
  }

  /** Cancel the reader; complete pending requests with errors. */
  def close(): Task[Unit] = Task {
    if (closed.compareAndSet(false, true)) {
      pending.values().forEach(_.failure(new McpError(-1, "Connection closed")))
      pending.clear()
      try input.close() catch { case _: Throwable => () }
      try output.close() catch { case _: Throwable => () }
    }
  }

  private def writeMessage(message: Json): Unit = writeLock.synchronized {
    val body = JsonFormatter.Compact(message) + "\n"
    output.write(body.getBytes(StandardCharsets.UTF_8))
    output.flush()
  }

  private def readerLoop(): Unit = {
    try {
      var line = input.readLine()
      while (line != null && !closed.get()) {
        if (line.nonEmpty) {
          try dispatch(JsonParser(line))
          catch { case t: Throwable => scribe.warn(s"MCP: dispatch failed: ${t.getMessage}") }
        }
        line = input.readLine()
      }
    } catch { case _: Throwable => () }
    closed.set(true)
    pending.values().forEach(_.failure(new McpError(-1, "Reader exited")))
    pending.clear()
  }

  private def dispatch(json: Json): Unit = {
    val idOpt     = json.get("id").flatMap(j => if (j == Null) None else Some(j))
    val methodOpt = json.get("method").flatMap(j => if (j == Null) None else Some(j.asString))

    (idOpt, methodOpt) match {
      case (Some(idJson), Some(method)) =>
        // Server-initiated request — invoke handler, write response back.
        val params  = json.get("params").getOrElse(Obj.empty)
        val replyId = idJson
        requestHandler(method, params).map { result =>
          writeMessage(obj("jsonrpc" -> str("2.0"), "id" -> replyId, "result" -> result))
        }.handleError { t =>
          Task {
            writeMessage(obj(
              "jsonrpc" -> str("2.0"),
              "id"      -> replyId,
              "error"   -> obj("code" -> num(-32603), "message" -> str(t.getMessage))
            ))
          }
        }.startUnit()

      case (Some(idJson), None) =>
        // Response to one of our outgoing requests.
        val id  = idJson.asInt
        val ref = Option(pending.remove(id))
        ref.foreach { ref =>
          json.get("error") match {
            case Some(err) if err != Null =>
              val code    = err.get("code").map(_.asInt).getOrElse(-1)
              val message = err.get("message").map(_.asString).getOrElse("Unknown MCP error")
              ref.failure(new McpError(code, message))
            case _ =>
              ref.success(json.get("result").getOrElse(Null))
          }
        }

      case (None, Some(method)) =>
        // Notification.
        val params = json.get("params").getOrElse(Obj.empty)
        notificationHandler(method, params).handleError(_ => Task.unit).startUnit()

      case _ => ()
    }
  }
}
