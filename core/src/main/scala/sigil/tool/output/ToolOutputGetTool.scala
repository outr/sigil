package sigil.tool.output

import fabric.{num, obj, str}
import fabric.io.JsonFormatter
import lightdb.id.Id
import rapid.{Stream, Task}
import sigil.TurnContext
import sigil.event.{Event, Message, MessageRole}
import sigil.signal.EventState
import sigil.storage.StoredFile
import sigil.tool.{ToolExample, ToolName, TypedTool}
import sigil.tool.model.ResponseContent

/**
 * `tool_output_get` — fetch the full payload (or a byte slice) of a
 * prior tool call's externalized output. Used by the agent when the
 * inline summary on a `ToolResults` event isn't enough and it needs
 * to drill into the raw / structured data.
 *
 * Authorization is delegated to [[sigil.Sigil.fetchStoredFile]] —
 * the caller's `accessibleSpaces` must include the StoredFile's
 * space, otherwise the lookup silently misses (returns
 * `ok = false, error = "not found or unauthorized"`).
 *
 * Slicing: when `start` and / or `length` are set, returns the
 * specified byte range as text (UTF-8). Useful for iterating through
 * a multi-megabyte log a chunk at a time without exhausting context.
 */
case object ToolOutputGetTool
  extends TypedTool[ToolOutputGetInput](
    name = ToolName("tool_output_get"),
    description =
      """Fetch the full payload (or a byte slice) of a prior tool call's externalized output by
        |`outputId`. The id comes from a `ToolResults` event whose `outputId` field was populated
        |when the tool's payload was too large to inline. Optional `start` + `length` return only
        |a byte range — useful for iterating a long log without loading the whole thing.""".stripMargin,
    examples = List(
      ToolExample("Fetch the full externalized output",
        ToolOutputGetInput(outputId = "abc123")),
      ToolExample("Fetch the first 4 KB of a long compile log",
        ToolOutputGetInput(outputId = "abc123", start = Some(0), length = Some(4096)))
    ),
    keywords = Set("tool", "output", "get", "fetch", "externalized")
  ) {

  override protected def executeTyped(input: ToolOutputGetInput, ctx: TurnContext): Stream[Event] =
    Stream.force(
      ctx.sigil.fetchStoredFile(Id[StoredFile](input.outputId), ctx.chain).map {
        case None =>
          emit(ctx, obj(
            "ok"    -> str("false"),
            "error" -> str(s"output ${input.outputId} not found or unauthorized")
          ))
        case Some((file, bytes)) =>
          val sliced = (input.start, input.length) match {
            case (Some(s), Some(l)) =>
              val from = s.toInt.max(0).min(bytes.length)
              val to   = (from + l.toInt.max(0)).min(bytes.length)
              bytes.slice(from, to)
            case (Some(s), None) =>
              val from = s.toInt.max(0).min(bytes.length)
              bytes.slice(from, bytes.length)
            case (None, Some(l)) =>
              val to = l.toInt.max(0).min(bytes.length)
              bytes.slice(0, to)
            case (None, None) => bytes
          }
          val payload = new String(sliced, "UTF-8")
          emit(ctx, obj(
            "ok"          -> str("true"),
            "outputId"    -> str(input.outputId),
            "contentType" -> str(file.contentType),
            "size"        -> num(file.size),
            "returned"    -> num(sliced.length.toLong),
            "content"     -> str(payload)
          ))
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
