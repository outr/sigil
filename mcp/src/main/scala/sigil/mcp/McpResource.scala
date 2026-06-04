package sigil.mcp

import fabric.Json
import fabric.rw.*

/**
 * Resource advertised by an MCP server, as discovered via `resources/list`.
 * `uri` is the server-defined identifier; clients fetch contents via
 * `resources/read`.
 */
case class McpResource(uri: String,
                       name: Option[String] = None,
                       description: Option[String] = None,
                       mimeType: Option[String] = None)
  derives RW

object McpResource {

  /**
   * Parse a single `resources/list` entry.
   */
  def fromJson(entry: Json): McpResource =
    McpResource(
      uri = entry.get("uri").map(_.asString).getOrElse(""),
      name = entry.get("name").map(_.asString),
      description = entry.get("description").map(_.asString),
      mimeType = entry.get("mimeType").map(_.asString)
    )

  /**
   * Parse the `resources` array out of a `resources/list` RPC result.
   */
  def listFrom(result: Json): List[McpResource] =
    result.get("resources").map(_.asVector.toList.map(fromJson)).getOrElse(Nil)
}
