package sigil.mcp

/**
 * JSON-RPC error surfaced from an MCP server. Carries the standard
 * `code` (negative for protocol errors, positive for application
 * errors) and `message`. Apps catching the throwable can pattern
 * match on `code` to decide retry semantics.
 */
final case class McpError(code: Int, message: String) extends RuntimeException(s"MCP error $code: $message")
