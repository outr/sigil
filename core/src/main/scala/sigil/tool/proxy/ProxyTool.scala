package sigil.tool.proxy

import fabric.rw.*
import lightdb.id.Id
import lightdb.time.Timestamp
import rapid.Stream
import sigil.{SpaceId, TurnContext}
import sigil.event.Event
import sigil.participant.ParticipantId
import sigil.provider.Mode
import sigil.tool.{Tool, ToolExample, ToolInput, ToolName, ToolSchema}

/**
 * Wraps an existing [[Tool]] so its execution is dispatched through
 * a [[ToolProxyTransport]] instead of running locally. The proxy
 * mimics the wrapped tool's surface — same name, description, input
 * schema, modes, spaces, keywords, examples — so the LLM sees an
 * identical tool. Only `execute` differs: it serializes the typed
 * input to JSON and hands it to the transport.
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
 * transport decides routing, framing, streaming, timeouts, and
 * failure semantics. The events emitted by the transport flow
 * through Sigil's normal event pipeline, so visibility, replay,
 * agent re-trigger, and persistence all work the same way they
 * would for a local tool.
 */
class ProxyTool(wrapped: Tool, transport: ToolProxyTransport) extends Tool {
  override def name: ToolName                         = wrapped.name
  override def description: String                    = wrapped.description
  override def inputRW: RW[? <: ToolInput]            = wrapped.inputRW
  override def inputDefinition: fabric.define.Definition = wrapped.inputDefinition
  override def modes: Set[Id[Mode]]                   = wrapped.modes
  override def space: SpaceId                         = wrapped.space
  override def keywords: Set[String]                  = wrapped.keywords
  override def examples: List[ToolExample]            = wrapped.examples
  override def createdBy: Option[ParticipantId]       = wrapped.createdBy
  override def _id: Id[Tool]                          = wrapped._id
  override def created: Timestamp                     = wrapped.created
  override def modified: Timestamp                    = wrapped.modified
  override lazy val schema: ToolSchema                = wrapped.schema

  override def execute(input: ToolInput, context: TurnContext): Stream[Event] = {
    val rw       = wrapped.inputRW.asInstanceOf[RW[ToolInput]]
    val rendered = rw.read(input)
    transport.dispatch(wrapped.name, rendered, context)
  }
}
