package sigil.mcp

import fabric.*
import fabric.io.JsonFormatter
import rapid.Task
import rapid.task.Completable

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Shared bidirectional dispatch for MCP transports. Owns:
 *
 *   - the request-id counter and a pending-request map for outgoing
 *     correlated requests
 *   - the routing decision for each incoming JSON-RPC message
 *     (response → complete pending; server request → invoke
 *     `requestHandler` and write back; notification → invoke
 *     `notificationHandler`)
 *
 * Transports plug in their own `send: Json => Unit` (write a single
 * JSON-RPC message to the wire) and call [[dispatchIncoming]] for
 * each message they read.
 *
 * Used by [[McpJsonRpc]] (stdio) and [[HttpSseMcpClient]] (HTTP+SSE).
 */
final class McpDispatcher(send: Json => Unit,
                          requestHandler: (String, Json) => Task[Json],
                          notificationHandler: (String, Json) => Task[Unit]) {

  private val nextId  = new AtomicInteger(1)
  private val pending = new ConcurrentHashMap[Int, Completable[Json]]()

  /** Send a request, returning a Task that completes with the
    * matching response. The wire id is reported via the callback so
    * callers can register the call for cancellation tracking. */
  def request(method: String, params: Json, onWireId: Long => Unit): Task[Json] = Task.defer {
    val id  = nextId.getAndIncrement()
    val ref = Task.completable[Json]
    pending.put(id, ref)
    onWireId(id.toLong)
    send(obj(
      "jsonrpc" -> str("2.0"),
      "id"      -> num(id),
      "method"  -> str(method),
      "params"  -> params
    ))
    ref
  }

  /** Send a notification; no response correlation. */
  def notify(method: String, params: Json): Task[Unit] = Task {
    send(obj(
      "jsonrpc" -> str("2.0"),
      "method"  -> str(method),
      "params"  -> params
    ))
  }

  /** Process one incoming JSON-RPC message. */
  def dispatchIncoming(json: Json): Unit = {
    val idOpt     = json.get("id").flatMap(j => if (j == Null) None else Some(j))
    val methodOpt = json.get("method").flatMap(j => if (j == Null) None else Some(j.asString))

    (idOpt, methodOpt) match {
      case (Some(idJson), Some(method)) =>
        // Server-initiated request — invoke handler, write response back.
        val params = json.get("params").getOrElse(Obj.empty)
        val replyId = idJson
        requestHandler(method, params).map { result =>
          send(obj("jsonrpc" -> str("2.0"), "id" -> replyId, "result" -> result))
        }.handleError { t =>
          Task {
            send(obj(
              "jsonrpc" -> str("2.0"),
              "id"      -> replyId,
              "error"   -> obj("code" -> num(-32603), "message" -> str(t.getMessage))
            ))
          }
        }.startUnit()

      case (Some(idJson), None) =>
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
        val params = json.get("params").getOrElse(Obj.empty)
        notificationHandler(method, params).handleError(_ => Task.unit).startUnit()

      case _ => ()
    }
  }

  /** Fail every pending request — called when the connection drops. */
  def failPending(t: Throwable): Unit = {
    pending.values().forEach(_.failure(t))
    pending.clear()
  }
}
