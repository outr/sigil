package sigil.provider

import fabric.Obj
import fabric.rw.*

/**
 * Events emitted by a provider during a streaming LLM response.
 *
 * The stream of ProviderEvents IS the response — there is no separate wrapper
 * carrying the final result. Consumers accumulate events they care about; `Done`
 * is the terminator that explains why the stream ended.
 *
 * Distinct from [[sigil.event.Event]], which represents persisted events in the
 * conversation log. ProviderEvents are ephemeral; Events are durable.
 */
enum ProviderEvent derives RW {
  case TextDelta(text: String)
  case ToolCallStart(callId: CallId, toolName: String)
  case ToolCallDelta(callId: CallId, argsDelta: String)
  case ToolCallComplete(callId: CallId, inputs: Obj)
  case ThinkingDelta(text: String)
  case Usage(usage: TokenUsage)
  case Done(stopReason: StopReason)
  case Error(message: String)
}
