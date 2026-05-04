package sigil.tool.model

import fabric.rw.*
import sigil.tool.ToolInput

/**
 * Input for `git_branch` — list local branches and identify the
 * current one. `includeRemotes = true` extends the listing with
 * remote-tracking branches.
 */
case class GitBranchInput(includeRemotes: Boolean = false,
                          workingDir: Option[String] = None) extends ToolInput derives RW
