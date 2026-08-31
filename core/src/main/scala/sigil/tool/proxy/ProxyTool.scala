package sigil.tool.proxy

import rapid.Task
import sigil.tool.{Resolution, Tool, ToolContext, ToolDecorator, ToolResult}

/**
 * [[ToolDecorator]] that dispatches execution through a
 * [[ToolProxyTransport]] instead of running locally. The proxy
 * forwards the wrapped tool's whole surface by construction — name,
 * description, schema, examples, and the full capability profile
 * (consent gate, destructive warning, preconditions, detachability) —
 * so the LLM sees an identical tool and the executor applies identical
 * gates. Only the resolution differs: it serializes the typed input to
 * JSON, hands it to the transport, and decodes the returned
 * [[ToolResult]] back to the wrapped tool's `Output`.
 *
 * Apps wire remote-execution by registering ProxyTools instead of
 * the local versions:
 *
 * {{{
 *   val localScriptTool = new ExecuteScriptTool(...)        // shape carrier
 *   val transport: ToolProxyTransport = ...                 // app-specific
 *   val remoteScriptTool = new ProxyTool(localScriptTool, transport)
 *
 *   // Agent sees `remoteScriptTool`; the LLM-visible schema is identical
 *   // to `localScriptTool`.
 * }}}
 *
 * The framework intentionally has no opinion on the wire — the
 * transport decides routing, framing, timeouts, and failure
 * semantics. The framework builds the paired result event from the
 * decoded resolution, so visibility, replay, agent re-trigger, and
 * persistence all work the same way they would for a local tool.
 */
class ProxyTool(val underlying: Tool, transport: ToolProxyTransport) extends ToolDecorator {

  /**
   * The decorated tool under its historical name.
   */
  def wrapped: Tool = underlying

  protected def resolve: Resolution[Input, Output] = Resolution.Explicit(dispatchRemote)

  private def dispatchRemote(input: Input, context: ToolContext): Task[ToolResult[Output]] = {
    val rendered = inputRW.read(input)
    transport.dispatch(underlying.name, rendered, context).map {
      case ToolResult.Success(json) => ToolResult.Success(outputRW.write(json))
      case failure: ToolResult.Failure => failure
    }
  }
}
