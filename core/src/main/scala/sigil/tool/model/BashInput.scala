package sigil.tool.model

import fabric.rw.*
import sigil.tool.ToolInput

/**
 * Input for `bash` — execute a shell command. `workingDir`
 * (optional) sets the cwd; `timeoutMs` defaults to 120 s.
 */
case class BashInput(command: String,
                     workingDir: Option[String] = None,
                     timeoutMs: Option[Long] = None) extends ToolInput derives RW
