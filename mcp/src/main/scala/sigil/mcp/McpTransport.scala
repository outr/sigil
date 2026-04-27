package sigil.mcp

import fabric.rw.*
import spice.net.URL

/**
 * Wire transport for an MCP connection. The 2024-11-05 spec defines two:
 *
 *   - [[Stdio]] — launch a subprocess; pipe newline-delimited JSON-RPC
 *     over its stdin/stdout. Most public MCP servers ship as stdio
 *     binaries.
 *   - [[HttpSse]] — POST JSON-RPC requests to a URL; server responds
 *     either inline (`Content-Type: application/json`) or via an
 *     event-stream that the client subscribes to. Used by hosted MCP
 *     servers.
 *
 * Apps select per-server via [[McpServerConfig.transport]]. The
 * framework's [[McpClient]] dispatches on this case at connect time.
 */
enum McpTransport derives RW {
  case Stdio(command: String, args: List[String] = Nil)
  case HttpSse(url: URL, headers: Map[String, String] = Map.empty)
}
