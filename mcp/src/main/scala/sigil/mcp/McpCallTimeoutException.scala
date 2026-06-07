package sigil.mcp

/**
 * Raised when an MCP tool call exceeds its server's
 * [[McpServerConfig.callTimeoutMs]]. The framework surfaces it to the agent
 * as a recoverable tool failure rather than letting a wedged or slow-importing
 * server hang the turn indefinitely. The message names the call and frames the
 * timeout as "server busy, retry" so the agent reacts instead of blocking.
 */
final class McpCallTimeoutException(serverName: String, toolName: String, timeoutMs: Long)
  extends RuntimeException(
    s"MCP tool `$toolName` on server `$serverName` did not respond within ${timeoutMs}ms — " +
      "the server may still be starting or importing. Retry shortly, or warm it first."
  )
