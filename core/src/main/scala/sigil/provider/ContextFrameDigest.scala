package sigil.provider

import sigil.conversation.{ContextFrame, ToolCallState}

/**
 * Cross-provider digest renderer for [[ContextFrame]]. Used by the
 * default [[Provider.appendFrame]] to build the encoded-context
 * buffer — a newline-delimited textual representation
 * that's opaque-but-readable. Providers with bespoke wire shapes
 * override `appendFrame` directly.
 */
object ContextFrameDigest {
  def render(frame: ContextFrame): String = frame match {
    case t: ContextFrame.Text =>
      s"[${t.participantId.value}] ${t.content}"
    case tc: ContextFrame.ToolCall =>
      // Sigil #261 — unified ToolCall(state) frame renders both the
      // call and (when Complete) its result in one digest entry.
      val resultPart = tc.state match {
        case ToolCallState.Complete(content, _) => s" → $content"
        case ToolCallState.Active => ""
      }
      s"[${tc.participantId.value}] tool ${tc.toolName.value}(${tc.argsJson}) #${tc.callId.value}$resultPart"
    case s: ContextFrame.System =>
      s"[system] ${s.content}"
    case r: ContextFrame.Reasoning =>
      s"[reasoning ${r.providerItemId}] ${r.summary.mkString(" ")}"
  }
}
