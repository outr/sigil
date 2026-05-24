package sigil.tool.proxy

import fabric.Json
import rapid.Task
import sigil.tool.ToolContext
import sigil.tool.{ToolName, ToolResult}

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
 *   - **Failure / timeout.** If the remote fails, disconnects, or
 *     times out, the transport resolves to a [[ToolResult.Failure]]
 *     describing the wire-level error.
 *
 * The remote side runs the wrapped tool and ships back its typed
 * resolution; the transport carries the success payload as fabric
 * `Json` (rendered through the remote tool's `outputRW`). [[ProxyTool]]
 * decodes it back to the wrapped tool's `Output` and the framework
 * builds the standard paired result event — so call/result pairing,
 * visibility, replay, and persistence all work exactly as they would
 * for a local tool.
 */
trait ToolProxyTransport {

  /**
   * Dispatch a remote tool invocation and return its resolution. The
   * proxy calls this from inside its `executeResult` body; the
   * framework turns the returned [[ToolResult]] into the paired
   * result event.
   */
  def dispatch(toolName: ToolName,
               inputJson: Json,
               context: ToolContext): Task[ToolResult[Json]]
}
