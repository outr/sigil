package sigil.tool.output

import fabric.rw.*
import sigil.tool.ToolInput

/** Input for `tool_output_summary` — returns the metadata of an
  * externalized tool output (size, contentType, expiresAt) without
  * loading the bytes. Helps the agent decide whether `tool_output_get`
  * (full fetch) or `tool_output_search` (targeted grep) is the right
  * next step. */
case class ToolOutputSummaryInput(outputId: String) extends ToolInput derives RW
