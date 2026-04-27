package sigil.tool.model

import fabric.rw.*
import sigil.tool.ToolInput

/**
 * Input for `grep` — regex search across files. `glob` optionally
 * restricts the file set (e.g. `**.scala`); `contextLines` controls
 * surrounding-context output.
 */
case class GrepInput(path: String,
                     pattern: String,
                     glob: Option[String] = None,
                     maxMatches: Int = 500,
                     contextLines: Int = 0) extends ToolInput derives RW
