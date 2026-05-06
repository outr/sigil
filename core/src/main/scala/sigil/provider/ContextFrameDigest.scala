package sigil.provider

import sigil.conversation.ContextFrame

/** Cross-provider digest renderer for [[ContextFrame]]. Used by the
  * default [[Provider.appendFrame]] to build the encoded-context
  * buffer (bug #26) — a newline-delimited textual representation
  * that's opaque-but-readable. Providers with bespoke wire shapes
  * override `appendFrame` directly. */
object ContextFrameDigest {
  def render(frame: ContextFrame): String = frame match {
    case t: ContextFrame.Text =>
      s"[${t.participantId.value}] ${t.content}"
    case tc: ContextFrame.ToolCall =>
      s"[${tc.participantId.value}] tool ${tc.toolName.value}(${tc.argsJson}) #${tc.callId.value}"
    case tr: ContextFrame.ToolResult =>
      s"[tool] result #${tr.callId.value}: ${tr.content}"
    case s: ContextFrame.System =>
      s"[system] ${s.content}"
    case r: ContextFrame.Reasoning =>
      s"[reasoning ${r.providerItemId}] ${r.summary.mkString(" ")}"
  }
}
