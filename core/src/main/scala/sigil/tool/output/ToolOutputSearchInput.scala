package sigil.tool.output

import fabric.rw.*
import sigil.tool.ToolInput

/** Input for `tool_output_search` — grep-equivalent over an
  * externalized tool-output payload. Returns each line matching
  * `pattern` (Java regex syntax) with optional surrounding context
  * lines. Lets the agent find specific markers in a long compile log
  * without loading the whole payload into context. */
case class ToolOutputSearchInput(outputId: String,
                                 pattern: String,
                                 contextLines: Option[Int] = None,
                                 maxMatches: Option[Int] = None) extends ToolInput derives RW
