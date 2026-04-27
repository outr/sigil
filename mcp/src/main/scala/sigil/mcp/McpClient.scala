package sigil.mcp

import fabric.{Json, Obj}
import rapid.Task

/**
 * Connection to a single MCP server. One instance per
 * [[McpServerConfig]]; managed by [[McpManager]].
 *
 * The contract:
 *   - [[start]] performs the initialize handshake (per the MCP
 *     2024-11-05 spec) and starts the wire reader.
 *   - [[listTools]] / [[callTool]] expose the server's tool surface.
 *   - [[listResources]] / [[readResource]] / [[listPrompts]] /
 *     [[getPrompt]] expose the rest of the MCP feature set.
 *   - [[close]] tears the connection down.
 *
 * Cancellation: [[cancelRequest]] sends `notifications/cancelled` to
 * the server for the given request id. The server is expected to
 * abort the in-flight call. Used by the framework when an agent
 * receives a `Stop` event during an in-flight MCP call.
 */
trait McpClient {

  /** The server this client connects to. */
  def config: McpServerConfig

  /** Run the initialize handshake and start the reader fiber. */
  def start(): Task[Unit]

  /** Tear the connection down. */
  def close(): Task[Unit]

  /** List tools advertised by the server. */
  def listTools(): Task[List[McpToolDefinition]]

  /** Call a tool. The server's response (the `CallToolResult` shape:
    * `{ content: [...], isError: bool }`) is returned as raw JSON;
    * [[McpTool.execute]] translates it to Sigil events.
    *
    * `onWireId` (when supplied) fires once with the wire-level
    * request id so the caller can register the in-flight call for
    * cancellation tracking. The id is passed to [[cancelRequest]]
    * to send `notifications/cancelled` for that specific call. */
  def callTool(name: String, arguments: Json, onWireId: Long => Unit = _ => ()): Task[Json]

  /** List resources advertised by the server. */
  def listResources(): Task[List[McpResource]]

  /** Read the contents of a resource by URI. Returns the
    * `ReadResourceResult` shape — `{ contents: [...] }`. */
  def readResource(uri: String): Task[Json]

  /** List prompt templates advertised by the server. */
  def listPrompts(): Task[List[McpPrompt]]

  /** Fetch a prompt by name with the given arguments. Returns the
    * `GetPromptResult` shape — `{ description, messages: [...] }`. */
  def getPrompt(name: String, arguments: Map[String, String] = Map.empty): Task[Json]

  /** Send `notifications/cancelled` for the given outgoing request id. */
  def cancelRequest(requestId: Long, reason: Option[String] = None): Task[Unit]
}
