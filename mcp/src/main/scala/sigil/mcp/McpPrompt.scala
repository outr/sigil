package sigil.mcp

import fabric.rw.*

/**
 * Prompt template advertised by an MCP server, as discovered via
 * `prompts/list`. `arguments` describes the parameters the prompt
 * accepts; clients fetch a populated prompt via `prompts/get`.
 */
case class McpPrompt(name: String,
                     description: Option[String] = None,
                     arguments: List[McpPromptArgument] = Nil) derives RW

case class McpPromptArgument(name: String,
                             description: Option[String] = None,
                             required: Boolean = false) derives RW
