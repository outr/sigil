package sigil.mcp

import fabric.Json
import fabric.rw.*

/**
 * Prompt template advertised by an MCP server, as discovered via
 * `prompts/list`. `arguments` describes the parameters the prompt
 * accepts; clients fetch a populated prompt via `prompts/get`.
 */
case class McpPrompt(name: String,
                     description: Option[String] = None,
                     arguments: List[McpPromptArgument] = Nil)
  derives RW

object McpPrompt {

  /**
   * Parse a single `prompts/list` entry.
   */
  def fromJson(entry: Json): McpPrompt =
    McpPrompt(
      name = entry.get("name").map(_.asString).getOrElse(""),
      description = entry.get("description").map(_.asString),
      arguments = entry.get("arguments").map(_.asVector.toList.map(McpPromptArgument.fromJson)).getOrElse(Nil)
    )

  /**
   * Parse the `prompts` array out of a `prompts/list` RPC result.
   */
  def listFrom(result: Json): List[McpPrompt] =
    result.get("prompts").map(_.asVector.toList.map(fromJson)).getOrElse(Nil)
}

case class McpPromptArgument(name: String,
                             description: Option[String] = None,
                             required: Boolean = false)
  derives RW

object McpPromptArgument {

  /**
   * Parse a single prompt-argument descriptor.
   */
  def fromJson(entry: Json): McpPromptArgument =
    McpPromptArgument(
      name = entry.get("name").map(_.asString).getOrElse(""),
      description = entry.get("description").map(_.asString),
      required = entry.get("required").map(_.asBoolean).getOrElse(false)
    )
}
