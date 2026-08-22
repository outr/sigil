package spec

import fabric.{Arr, Json, Obj, obj, str}
import rapid.Task
import sigil.mcp.{McpClient, McpPrompt, McpResource, McpServerConfig, McpToolDefinition}

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

/**
 * Stand-in for an app-supplied [[McpClient]] that speaks JSON-RPC over
 * a transport the framework doesn't construct — here, nothing at all:
 * the canned tool list and call results are served in-process, so the
 * spec exercises the client-factory seam without a socket or a
 * subprocess.
 *
 * Records `start()` calls, counts `listTools()` round-trips (so cache
 * invalidation is observable), and keeps every `callTool` argument set.
 * [[fireToolsListChanged]] pushes a server notification back through the
 * listener the manager handed over.
 */
final class InProcessMcpClient(override val config: McpServerConfig,
                               tools: List[McpToolDefinition],
                               notificationListener: (String, Json) => Task[Unit]) extends McpClient {
  val starts: AtomicInteger = new AtomicInteger(0)
  val listToolsCalls: AtomicInteger = new AtomicInteger(0)
  val toolCalls: ConcurrentLinkedQueue[(String, Json)] = new ConcurrentLinkedQueue()

  override def start(): Task[Unit] = Task {
    starts.incrementAndGet()
    ()
  }

  override def close(): Task[Unit] = Task.unit

  override def listTools(): Task[List[McpToolDefinition]] = Task {
    listToolsCalls.incrementAndGet()
    tools
  }

  override def callTool(name: String, arguments: Json, onWireId: Long => Unit = _ => ()): Task[Json] = Task {
    toolCalls.add((name, arguments))
    onWireId(1L)
    obj("content" -> Arr(Vector(obj("type" -> str("text"), "text" -> str(s"$name handled in-process")))))
  }

  override def listResources(): Task[List[McpResource]] = Task.pure(Nil)

  override def readResource(uri: String): Task[Json] = Task.pure(Obj.empty)

  override def listPrompts(): Task[List[McpPrompt]] = Task.pure(Nil)

  override def getPrompt(name: String, arguments: Map[String, String] = Map.empty): Task[Json] = Task.pure(Obj.empty)

  override def cancelRequest(requestId: Long, reason: Option[String] = None): Task[Unit] = Task.unit

  /** Push a `notifications/tools/list_changed` back through the listener
    * the manager supplied, the way a real server would. */
  def fireToolsListChanged(): Task[Unit] = notificationListener("notifications/tools/list_changed", Obj.empty)
}
