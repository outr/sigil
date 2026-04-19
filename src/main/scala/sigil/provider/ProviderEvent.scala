package sigil.provider

import fabric.Obj
import fabric.io.JsonFormatter
import fabric.rw.*
import sigil.tool.ToolInput

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
  case ToolCallComplete(callId: CallId, input: ToolInput)
  case ContentBlockStart(callId: CallId, blockType: String, arg: Option[String])
  case ContentBlockDelta(callId: CallId, text: String)
  case ThinkingDelta(text: String)
  case Usage(usage: TokenUsage)
  case Done(stopReason: StopReason)
  case Error(message: String)

  def asString: String =
    this match {
      case TextDelta(text) => s"TextDelta($text)"
      case ToolCallStart(_, toolName) => s"ToolCallStart($toolName)"
      case ToolCallComplete(_, input) => s"ToolCallComplete($input)"
      case ContentBlockStart(_, t, a) => s"ContentBlockStart($t${a.fold("")(v => s" $v")})"
      case ContentBlockDelta(_, t) => s"ContentBlockDelta($t)"
      case ThinkingDelta(text) => s"ThinkingDelta($text)"
      case Usage(_) => "Usage"
      case Done(stopReason) => s"Done(${stopReason.toString})"
      case Error(message) => s"Error($message)"
    }
}
