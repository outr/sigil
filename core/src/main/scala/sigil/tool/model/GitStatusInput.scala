package sigil.tool.model

import fabric.rw.*
import sigil.tool.ToolInput

/**
 * Input for `git_status` — read working-tree status. `workingDir`
 * (optional) overrides the conversation's workspace for repos
 * outside the default project root.
 */
case class GitStatusInput(workingDir: Option[String] = None) extends ToolInput derives RW
