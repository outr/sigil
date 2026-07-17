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
                             inputSchema: Json = Obj.empty)
  derives RW

object McpToolDefinition {

  /**
   * Parse a single `tools/list` entry.
   */
  def fromJson(entry: Json): McpToolDefinition =
    McpToolDefinition(
      name = entry.get("name").map(_.asString).getOrElse(""),
      description = entry.get("description").map(_.asString),
      inputSchema = entry.get("inputSchema").getOrElse(Obj.empty)
    )

  /**
   * Parse the `tools` array out of a `tools/list` RPC result.
   */
  def listFrom(result: Json): List[McpToolDefinition] =
    result.get("tools").map(_.asVector.toList.map(fromJson)).getOrElse(Nil)
}
