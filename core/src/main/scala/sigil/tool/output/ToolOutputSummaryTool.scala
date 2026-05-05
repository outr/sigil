package sigil.tool.output

import lightdb.id.Id
import rapid.Task
import sigil.TurnContext
import sigil.storage.StoredFile
import sigil.tool.{ToolExample, ToolName, TypedOutputTool}

/**
 * `tool_output_summary` — return metadata about an externalized
 * tool-output payload (size, contentType, expiresAt, line count when
 * applicable) without loading the bytes. Cheap; helps the agent
 * decide whether `tool_output_get` (full fetch) or
 * `tool_output_search` (targeted grep) is the right next step.
 *
 * Authorization-gated through [[sigil.Sigil.fetchStoredFile]] —
 * an unauthorized caller gets [[ToolOutputSummaryResult.NotFound]].
 *
 * Emits a typed [[ToolOutputSummaryResult]] (`Found | NotFound`).
 */
case object ToolOutputSummaryTool
  extends TypedOutputTool[ToolOutputSummaryInput, ToolOutputSummaryResult](
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

  override protected def executeTyped(input: ToolOutputSummaryInput, ctx: TurnContext): Task[ToolOutputSummaryResult] =
    ctx.sigil.fetchStoredFile(Id[StoredFile](input.outputId), ctx.chain).map {
      case None =>
        ToolOutputSummaryResult.NotFound(
          outputId = input.outputId,
          error    = s"output ${input.outputId} not found or unauthorized"
        )
      case Some((file, bytes)) =>
        val lineCount = if (file.contentType.startsWith("text/") || file.contentType == "application/json") {
          // Counting newlines is fine; final-line-without-newline is fine too — the agent uses
          // this as an order-of-magnitude hint, not a precise count.
          new String(bytes, "UTF-8").count(_ == '\n').toLong
        } else 0L
        ToolOutputSummaryResult.Found(
          outputId    = input.outputId,
          contentType = file.contentType,
          size        = file.size,
          lineCount   = lineCount,
          expiresAt   = file.expiresAt.map(_.value),
          category    = file.category.toString
        )
    }
}
