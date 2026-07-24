package sigil.tool.model

import fabric.rw.*
import sigil.tool.ToolInput

/**
 * Input for `git_commit` — stage `paths` (or every tracked change
 * when omitted) and commit with `message`. Writes; apps gate this
 * tool the same way they gate `delete_file` — typically opt-in for
 * agents that need to author commits.
 */
case class GitCommitInput(message: String,
                          paths: Option[List[String]] = None,
                          allowEmpty: Boolean = false,
                          workingDir: Option[String] = None)
  extends ToolInput derives RW
