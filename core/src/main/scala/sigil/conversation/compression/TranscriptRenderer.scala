package sigil.conversation.compression

import sigil.conversation.{ContextFrame, TopicEntry}
import sigil.provider.Mode

/**
 * Shared plain-text rendering of [[ContextFrame]] runs for LLM-driven
 * summarization / memory extraction. Pairs each `ToolCall` with its
 * `ToolResult` inline so the reading model sees a coherent
 * request/response unit rather than two disjoint lines.
 *
 * Header lines establish scope (active mode, active topic) so the
 * consulted model knows what the excerpt is *about* — without this,
 * summaries drift toward generic paraphrase. Headers are optional:
 * pass `None` on calls where the scope isn't meaningful (e.g. a
 * whole-conversation extraction pass).
 */
object TranscriptRenderer {

  /**
   * Render a frame run as plain text. Header values (mode, topic) are
   * rendered when supplied.
   */
  def render(frames: Vector[ContextFrame],
             mode: Option[Mode] = None,
             topic: Option[TopicEntry] = None): String = {
    val sb = new StringBuilder
    mode.foreach(m => sb.append(s"Active mode: $m — ${m.description}\n"))
    topic.foreach(t => sb.append(s"Active topic: \"${t.label}\" — ${t.summary}\n"))
    if (sb.nonEmpty) sb.append("\n")

    frames.foreach {
      case ContextFrame.Text(content, participantId, _, _) =>
        sb.append(s"[${participantId.value}] ").append(content.trim).append("\n")

      case tc: ContextFrame.ToolCall =>
        // Sigil #261 — unified ToolCall(state) frame: render the call
        // line; when Complete, render the inline result on the next
        // line. No separate ToolResult frame to pair (or orphan).
        sb.append(s"[${tc.participantId.value} → ${tc.toolName.value}] ").append(tc.argsJson).append("\n")
        tc.state match {
          case sigil.conversation.ToolCallState.Complete(content, _) =>
            sb.append(s"  ↳ ").append(content.trim).append("\n")
          case sigil.conversation.ToolCallState.Active =>
        }

      case ContextFrame.System(content, _, _) =>
        sb.append("[system] ").append(content.trim).append("\n")

      case _: ContextFrame.Reasoning =>
      // Provider-internal reasoning state (bug #61) — opaque to the
      // transcript view; skip.
    }
    sb.toString
  }
}
