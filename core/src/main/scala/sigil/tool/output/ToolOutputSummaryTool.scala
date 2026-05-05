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
 * `tool_output_summary` — return metadata about an externalized
 * tool-output payload (size, contentType, expiresAt, line count when
 * applicable) without loading the bytes. Cheap; helps the agent
 * decide whether `tool_output_get` (full fetch) or
 * `tool_output_search` (targeted grep) is the right next step.
 *
 * Authorization-gated through [[sigil.Sigil.fetchStoredFile]] —
 * an unauthorized caller gets `ok = false`.
 */
case object ToolOutputSummaryTool
  extends TypedTool[ToolOutputSummaryInput](
    name = ToolName("tool_output_summary"),
    description =
      """Return metadata about an externalized tool-output payload (size, contentType, expiresAt,
        |line count) without loading the bytes. Use this to decide whether to call
        |`tool_output_get` (full fetch) or `tool_output_search` (targeted grep).""".stripMargin,
    examples = List(
      ToolExample("Inspect an externalized output's size and type",
        ToolOutputSummaryInput(outputId = "abc123"))
    ),
    keywords = Set("tool", "output", "summary", "metadata", "size")
  ) {

  override protected def executeTyped(input: ToolOutputSummaryInput, ctx: TurnContext): Stream[Event] =
    Stream.force(
      ctx.sigil.fetchStoredFile(Id[StoredFile](input.outputId), ctx.chain).map {
        case None =>
          emit(ctx, obj(
            "ok"    -> str("false"),
            "error" -> str(s"output ${input.outputId} not found or unauthorized")
          ))
        case Some((file, bytes)) =>
          val lineCount = if (file.contentType.startsWith("text/") || file.contentType == "application/json") {
            // Counting newlines is fine; final-line-without-newline is fine too — the agent uses
            // this as an order-of-magnitude hint, not a precise count.
            new String(bytes, "UTF-8").count(_ == '\n')
          } else 0
          val expiresAt = file.expiresAt.map(_.value.toString).getOrElse("never")
          emit(ctx, obj(
            "ok"          -> str("true"),
            "outputId"    -> str(input.outputId),
            "contentType" -> str(file.contentType),
            "size"        -> num(file.size),
            "lineCount"   -> num(lineCount.toLong),
            "expiresAt"   -> str(expiresAt),
            "category"    -> str(file.category.toString)
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
