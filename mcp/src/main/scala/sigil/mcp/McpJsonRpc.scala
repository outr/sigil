package sigil.mcp

import fabric.*
import fabric.io.{JsonFormatter, JsonParser}
import rapid.Task

import java.io.{BufferedReader, OutputStream}
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Stdio transport's bidirectional JSON-RPC 2.0 message pump.
 * Delegates routing to [[McpDispatcher]] and adds a reader fiber
 * that drains newline-delimited input and a writer with newline-
 * terminated output.
 *
 * Wire format per the MCP 2024-11-05 stdio spec: one JSON object per
 * line, terminated by `\n`.
 */
final class McpJsonRpc(input: BufferedReader,
                       output: OutputStream,
                       requestHandler: (String, Json) => Task[Json] = (m, _) =>
                         Task.error(new McpError(-32601, s"No request handler for $m")),
                       notificationHandler: (String, Json) => Task[Unit] = (_, _) => Task.unit) {

  private val closed    = new AtomicBoolean(false)
  private val writeLock = new Object

  private val dispatcher = new McpDispatcher(
    send                = writeMessage,
    requestHandler      = requestHandler,
    notificationHandler = notificationHandler
  )

  /** Send a request and await the matching response. */
  def request(method: String, params: Json = Obj.empty): Task[Json] =
    dispatcher.request(method, params, _ => ())

  /** Send a request with a wire-id callback (for cancellation tracking). */
  def requestWithId(method: String, params: Json, onWireId: Long => Unit): Task[Json] =
    dispatcher.request(method, params, onWireId)

  /** Send a notification — no response expected. */
  def notify(method: String, params: Json = Obj.empty): Task[Unit] = Task.defer {
    if (closed.get()) Task.unit else dispatcher.notify(method, params)
  }

  /** Start the reader thread. Must be called once before any incoming-message dispatch.
    * Uses a plain daemon Thread (not a rapid fiber) — blocking line-reads on a
    * subprocess pipe interact poorly with the rapid scheduler's virtual-thread
    * accounting in some scenarios; an explicit thread sidesteps the issue. */
  def start(): Task[Unit] = Task {
    val t = new Thread(() => readerLoop(), s"sigil-mcp-reader")
    t.setDaemon(true)
    t.start()
    ()
  }

  /**
   * Mark the connection closed and fail pending requests. Does NOT
   * close the underlying input stream — `BufferedReader.close` would
   * deadlock with a reader thread parked inside `readLine()` (both
   * synchronize on the same intrinsic monitor). Owners of the
   * underlying streams (e.g. [[StdioMcpClient]]) are responsible for
   * destroying the process / closing the pipes; once the input
   * pipe yields EOF, the reader thread exits naturally. */
  def close(): Task[Unit] = Task {
    if (closed.compareAndSet(false, true)) {
      dispatcher.failPending(new McpError(-1, "Connection closed"))
      try output.close() catch { case _: Throwable => () }
    }
  }

  private def writeMessage(message: Json): Unit = writeLock.synchronized {
    if (closed.get()) return
    val body = JsonFormatter.Compact(message) + "\n"
    output.write(body.getBytes(StandardCharsets.UTF_8))
    output.flush()
  }

  private def readerLoop(): Unit = {
    try {
      var line = input.readLine()
      while (line != null && !closed.get()) {
        if (line.nonEmpty) {
          try dispatcher.dispatchIncoming(JsonParser(line))
          catch { case t: Throwable => scribe.warn(s"MCP: dispatch failed: ${t.getMessage}") }
        }
        line = input.readLine()
      }
    } catch { case _: Throwable => () }
    closed.set(true)
    dispatcher.failPending(new McpError(-1, "Reader exited"))
  }
}
