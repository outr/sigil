package sigil.tool.output

import fabric.rw.*

/** Typed result for [[ToolOutputSummaryTool]]. `expiresAt` rides
  * as a numeric epoch-millis when present and `None` for permanent
  * records (`UserAttachment` typically). `lineCount` is `0` for
  * non-text payloads. */
enum ToolOutputSummaryResult derives RW {
  case Found(outputId: String,
             contentType: String,
             size: Long,
             lineCount: Long,
             expiresAt: Option[Long],
             category: String)
  case NotFound(outputId: String, error: String)
}
