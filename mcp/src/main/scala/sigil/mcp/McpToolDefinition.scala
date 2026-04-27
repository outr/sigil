package sigil.mcp

import fabric.{Json, Obj}
import fabric.rw.*

/**
 * Tool advertised by an MCP server, as discovered via `tools/list`.
 * `inputSchema` is the raw JSON Schema the server declares — surfaced
 * directly to the LLM by [[McpTool]]'s `inputDefinition` override
 * (no Scala-side schema generation).
 */
case class McpToolDefinition(name: String,
                             description: Option[String] = None,
                             inputSchema: Json = Obj.empty) derives RW
