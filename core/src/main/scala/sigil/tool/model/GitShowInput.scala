package sigil.tool.model

import fabric.rw.*
import sigil.tool.ToolInput

/**
 * Input for `git_show` — render a single commit (metadata + diff).
 * `sha` accepts any git revision spec (`HEAD`, `HEAD~1`, a short
 * sha, a tag).
 */
case class GitShowInput(sha: String,
                        workingDir: Option[String] = None)
  extends ToolInput derives RW
