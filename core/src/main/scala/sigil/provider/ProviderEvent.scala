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

  /** A server-managed [[BuiltInTool]] began executing on the provider
    * side. Informational — no client action needed; the result (if
    * any) arrives inline in subsequent events or as part of the final
    * response. `query` is the tool-specific argument the model chose
    * (e.g. the web search query, the image generation prompt), when
    * the provider exposes it in the stream. */
  case ServerToolStart(callId: CallId, tool: BuiltInTool, query: Option[String])

  /** The server-managed tool finished. Emitted for observability /
    * logging; most consumers don't need to handle it. */
  case ServerToolComplete(callId: CallId, tool: BuiltInTool)

  /** A streamed partial image (base64 data URL or remote URL). The
    * provider may emit several of these with progressively higher
    * quality before the final [[ImageGenerationComplete]]. */
  case ImageGenerationPartial(callId: CallId, imageUrl: String)

  /** The final, fully-rendered generated image. Consumers typically
    * materialize this into a [[sigil.tool.model.ResponseContent.Image]]
    * block on the outgoing [[sigil.event.Message]]. */
  case ImageGenerationComplete(callId: CallId, imageUrl: String)

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
      case ServerToolStart(_, tool, q) => s"ServerToolStart($tool${q.fold("")(v => s" $v")})"
      case ServerToolComplete(_, tool) => s"ServerToolComplete($tool)"
      case ImageGenerationPartial(_, _) => "ImageGenerationPartial"
      case ImageGenerationComplete(_, url) => s"ImageGenerationComplete($url)"
      case Usage(_) => "Usage"
      case Done(stopReason) => s"Done(${stopReason.toString})"
      case Error(message) => s"Error($message)"
    }
}
