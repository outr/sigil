package sigil.mcp

import fabric.rw.*

/**
 * Resource advertised by an MCP server, as discovered via `resources/list`.
 * `uri` is the server-defined identifier; clients fetch contents via
 * `resources/read`.
 */
case class McpResource(uri: String,
                       name: Option[String] = None,
                       description: Option[String] = None,
                       mimeType: Option[String] = None) derives RW
