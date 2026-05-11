package sigil.tool.fs

import fabric.{Json, Obj, Str}
import sigil.TurnContext
import sigil.event.{ToolOutcome, ToolResults}
import sigil.signal.EventState

/**
 * Internal helper for the `sigil.tool.*` families that use
 * `TypedTool[I]` (not `TypedOutputTool[I, O]`) — emits a tool
 * result as a [[ToolResults]] event with the structured payload
 * preserved in `typed`, paired to the originating `ToolInvoke` via
 * `origin`.
 *
 * Bug #134 — earlier shape returned a `MessageRole.Tool` Message
 * whose `content` was the JSON-stringified payload. That had two
 * problems:
 *
 *   1. The Message wasn't linked to the originating ToolInvoke
 *      (no `origin`), so clients merging invoke + result into one
 *      tool chip fell through and rendered the result as a
 *      separate Message bubble.
 *   2. The typed structure was opaqued through `JsonFormatter.Compact`
 *      into a single-line escaped string. Diffs / multi-line outputs
 *      rendered as `\n`-escaped JSON blobs unreadable in chat UIs.
 *
 * Post-fix: typed payload rides through as `ToolResults.typed`;
 * clients render structured shapes (`{text: "diff content..."}` →
 * syntax-highlighted code block, `{hunks: [...]}` → structured diff
 * list, etc.). `outcome` is `Failure` when the payload carries an
 * `error` key; otherwise `Success`. `summary` is a chip-friendly
 * one-line description inferred from common payload shapes.
 */
object FsToolEmit {

  def apply(payload: Json, ctx: TurnContext): ToolResults = {
    val outcome: ToolOutcome = payload match {
      case o: Obj if o.value.contains("error") =>
        val msg = o.value.get("error").collect { case s: Str => s.value }.getOrElse("tool reported error")
        ToolOutcome.Failure(reason = msg, recoverable = true)
      case _ => ToolOutcome.Success
    }
    ToolResults(
      schemas        = Nil,
      participantId  = ctx.caller,
      conversationId = ctx.conversation.id,
      topicId        = ctx.conversation.currentTopicId,
      outcome        = outcome,
      summary        = inferSummary(payload),
      typed          = Some(payload),
      origin         = ctx.currentToolInvokeId,
      state          = EventState.Complete
    )
  }

  /** Best-effort chip-friendly summary derived from common payload
    * shapes. Returns `None` for shapes we don't recognise; the
    * client falls back to its own default (typically the tool
    * name + duration). */
  private def inferSummary(payload: Json): Option[String] = payload match {
    case o: Obj =>
      o.value.get("error").collect { case s: Str => s.value }.map { e =>
        val short = if (e.length <= 80) e else e.take(80) + "…"
        s"failed: $short"
      }.orElse {
        o.value.get("text").collect { case s: Str => s.value }.map { t =>
          val lines = t.linesIterator.length
          s"$lines line${if (lines == 1) "" else "s"}"
        }
      }.orElse {
        o.value.get("hunks").collect { case a: fabric.Arr => a.value.size }.map { n =>
          s"$n hunk${if (n == 1) "" else "s"}"
        }
      }.orElse {
        o.value.get("files").collect { case a: fabric.Arr => a.value.size }.map { n =>
          s"$n file${if (n == 1) "" else "s"}"
        }
      }
    case _ => None
  }
}
