package sigil.tool.proxy

import fabric.Json
import rapid.Stream
import sigil.TurnContext
import sigil.event.Event
import sigil.tool.ToolName

/**
 * Abstract dispatch boundary for [[ProxyTool]] — decouples the
 * proxy's "this tool runs elsewhere" semantics from the wire that
 * actually carries the call.
 *
 * The framework ships [[ProxyTool]] (the wrapper) but no concrete
 * transport. Apps implement a transport for their own infrastructure
 * — a DurableSocket bridge to a per-user client, an HTTP roundtrip
 * to a tool server, an in-process channel for tests, etc. — and
 * inject it into ProxyTool instances at registration time.
 *
 * Implementations decide:
 *   - **Wire format.** The tool's input arrives as fabric `Json`
 *     (already serialized through the wrapped tool's `inputRW`).
 *     The transport packages it however the remote endpoint
 *     expects.
 *   - **Routing.** Which remote endpoint receives the call —
 *     typically derived from the [[TurnContext]] (e.g. "the user
 *     in the chain", "the load-balanced executor pool", a fixed
 *     host).
 *   - **Streaming.** The returned `Stream[Event]` may emit a single
 *     terminal event (sync request/response) or stream partial
 *     results as the remote produces them. The proxy passes the
 *     stream through unchanged.
 *   - **Failure / timeout.** If the remote fails, disconnects, or
 *     times out, the transport emits whichever event(s) the app's
 *     error model expects (a `MessageRole.Tool` event with an error
 *     payload is the conventional shape).
 *
 * The framework does not interpret the events the transport emits —
 * they flow into the agent's signal stream as-is, treated like any
 * other tool result.
 */
trait ToolProxyTransport {

  /**
   * Dispatch a remote tool invocation and return the resulting
   * event stream. The proxy calls this from inside its `execute`
   * body; the orchestrator consumes the stream as if it were a
   * local tool's output.
   */
  def dispatch(toolName: ToolName,
               inputJson: Json,
               context: TurnContext): Stream[Event]
}
