package sigil.tool.proxy

import lightdb.id.Id
import lightdb.time.Timestamp
import rapid.Task
import sigil.tool.ToolContext
import sigil.participant.ParticipantId
import sigil.tool.{Resolution, Tool, ToolIO, ToolResult, ToolSpec}

/**
 * Wraps an existing [[Tool]] so its execution is dispatched through
 * a [[ToolProxyTransport]] instead of running locally. The proxy
 * mimics the wrapped tool's surface — same name, description, input
 * schema, modes, spaces, keywords, examples — so the LLM sees an
 * identical tool. Only the resolution differs: it serializes the
 * typed input to JSON, hands it to the transport, and decodes the
 * returned [[ToolResult]] back to the wrapped tool's `Output`.
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
class ProxyTool(val wrapped: Tool, transport: ToolProxyTransport) extends Tool {
  type Input  = wrapped.Input
  type Output = wrapped.Output

  /** Capabilities forwarded by construction — the proxy carries the
    * wrapped tool's whole spec (effect, gates, execution, discovery),
    * so a consent gate or destructive warning can never be dropped by
    * proxying. */
  val spec: ToolSpec = wrapped.spec

  /** Wire shape forwarded by construction — schema, codecs, and
    * examples are the wrapped tool's own. */
  def io: ToolIO[Input, Output] = wrapped.io

  override def createdBy: Option[ParticipantId] = wrapped.createdBy
  override def _id: Id[Tool]                    = wrapped._id
  override def created: Timestamp               = wrapped.created
  override def modified: Timestamp              = wrapped.modified

  protected def resolve: Resolution[Input, Output] = Resolution.Explicit(dispatchRemote)

  private def dispatchRemote(input: Input, context: ToolContext): Task[ToolResult[Output]] = {
    val rendered = inputRW.read(input)
    transport.dispatch(wrapped.name, rendered, context).map {
      case ToolResult.Success(json)    => ToolResult.Success(outputRW.write(json))
      case failure: ToolResult.Failure => failure
    }
  }
}
