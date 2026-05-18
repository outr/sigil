package sigil.tool.model

import fabric.rw.*
import sigil.tool.ToolInput

/**
 * Input for `git_diff`. `path` (optional) restricts the diff to a
 * single file or directory; `staged = true` diffs the index against
 * HEAD instead of working tree against index. `format = "text"`
 * (default) returns the raw unified-diff string; `format = "hunks"`
 * returns a structured hunk array.
 */
case class GitDiffInput(path: Option[String] = None,
                        staged: Boolean = false,
                        format: String = "text",
                        workingDir: Option[String] = None)
  extends ToolInput derives RW
