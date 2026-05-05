package sigil.tool.output

import fabric.rw.*
import sigil.tool.ToolInput

/** Input for `tool_output_get` — fetches the externalized payload of
  * a prior tool call by `outputId`. Optional `range` returns a slice
  * of the raw bytes (interpreted as text); when omitted the full
  * payload is returned. */
case class ToolOutputGetInput(outputId: String,
                              start: Option[Long] = None,
                              length: Option[Long] = None) extends ToolInput derives RW
