package sigil.mcp

import fabric.{Json, Obj}
import rapid.Task

/**
 * Callback invoked when an MCP server makes a `sampling/createMessage`
 * request — the server is asking the host's LLM to generate a
 * completion. The default handler (when [[McpServerConfig.samplingModelId]]
 * is unset) fails the request with an explanatory error; apps that
 * want sampling support either set `samplingModelId` (which uses the
 * framework's [[sigil.Sigil.providerFor]] to delegate) or supply a
 * custom handler on `McpSigil.samplingHandler`.
 *
 * The handler receives the raw `params` JSON from the server (the
 * MCP `CreateMessageRequest` shape); returns the response JSON
 * matching `CreateMessageResult`.
 */
trait SamplingHandler {
  def handle(serverName: String, params: Json): Task[Json]
}

object SamplingHandler {

  /** Default — refuses every sampling request with a clear error. */
  val Refusing: SamplingHandler = new SamplingHandler {
    override def handle(serverName: String, params: Json): Task[Json] =
      Task.error(new McpError(
        -32601,
        s"Sampling not configured for MCP server '$serverName' (set samplingModelId or override McpSigil.samplingHandler)."
      ))
  }
}
