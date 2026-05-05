package sigil.tool.output

import fabric.{arr, num, obj, str}
import fabric.io.JsonFormatter
import lightdb.id.Id
import rapid.Stream
import sigil.TurnContext
import sigil.event.{Event, Message, MessageRole}
import sigil.signal.EventState
import sigil.storage.StoredFile
import sigil.tool.{ToolExample, ToolName, TypedTool}
import sigil.tool.model.ResponseContent

/**
 * `tool_output_search` — line-grep over an externalized tool-output
 * payload, returning each match plus optional context lines. Lets
 * the agent locate specific markers in a long compile log / large
 * grep result / workspace-symbols dump without loading the full
 * payload through `tool_output_get`.
 *
 * `pattern` is a Java regex (case-sensitive). Lines are split on
 * `\n`. `contextLines` (default 0) emits N lines before and after
 * each match. `maxMatches` (default 100) caps results so a wildly
 * matching pattern can't fill context with the whole payload.
 */
case object ToolOutputSearchTool
  extends TypedTool[ToolOutputSearchInput](
    name = ToolName("tool_output_search"),
    description =
      """Search an externalized tool-output payload for lines matching a regex `pattern`.
        |Returns matches with optional context lines. Useful for finding specific markers
        |in a long compile log or large grep result without fetching the whole payload via
        |`tool_output_get`. `maxMatches` caps results (default 100).""".stripMargin,
    examples = List(
      ToolExample("Find every error in a compile log",
        ToolOutputSearchInput(outputId = "abc123", pattern = "(?i)error|failed", contextLines = Some(2))),
      ToolExample("Find usages of a symbol in workspace-symbols dump",
        ToolOutputSearchInput(outputId = "abc123", pattern = "MyClass\\b", maxMatches = Some(20)))
    ),
    keywords = Set("tool", "output", "search", "grep", "find")
  ) {

  override protected def executeTyped(input: ToolOutputSearchInput, ctx: TurnContext): Stream[Event] =
    Stream.force(
      ctx.sigil.fetchStoredFile(Id[StoredFile](input.outputId), ctx.chain).map {
        case None =>
          emit(ctx, obj(
            "ok"    -> str("false"),
            "error" -> str(s"output ${input.outputId} not found or unauthorized")
          ))
        case Some((_, bytes)) =>
          val text     = new String(bytes, "UTF-8")
          val lines    = text.split("\n", -1)
          val regex    = scala.util.Try(input.pattern.r).toOption
          val ctxLines = input.contextLines.getOrElse(0).max(0)
          val cap      = input.maxMatches.getOrElse(100).max(1)
          regex match {
            case None =>
              emit(ctx, obj(
                "ok"    -> str("false"),
                "error" -> str(s"invalid regex: ${input.pattern}")
              ))
            case Some(r) =>
              val matchedIndexes = lines.indices.filter(i => r.findFirstIn(lines(i)).isDefined).take(cap).toList
              val matches = matchedIndexes.map { i =>
                val from   = (i - ctxLines).max(0)
                val to     = (i + ctxLines + 1).min(lines.length)
                val window = lines.slice(from, to).mkString("\n")
                obj(
                  "lineNumber" -> num((i + 1).toLong),
                  "match"      -> str(lines(i)),
                  "context"    -> str(window)
                )
              }
              emit(ctx, obj(
                "ok"         -> str("true"),
                "outputId"   -> str(input.outputId),
                "pattern"    -> str(input.pattern),
                "totalLines" -> num(lines.length.toLong),
                "matches"    -> arr(matches *),
                "truncated"  -> str(if (matchedIndexes.size == cap) "true" else "false")
              ))
          }
      }
    )

  private def emit(ctx: TurnContext, payload: fabric.Json): Stream[Event] =
    Stream.emit[Event](Message(
      participantId  = ctx.caller,
      conversationId = ctx.conversation.id,
      topicId        = ctx.conversation.currentTopicId,
      content        = Vector(ResponseContent.Text(JsonFormatter.Compact(payload))),
      state          = EventState.Complete,
      role           = MessageRole.Tool
    ))
}
