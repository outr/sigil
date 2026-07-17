package sigil.tool.model

import fabric.rw.*

/**
 * One hunk of a unified diff. `file` is the affected path (the
 * `b/`-side for renames); `oldStart` / `newStart` are the 1-based
 * line numbers the hunk begins at in the old / new revision;
 * `lines` is the ordered run of context / add / remove lines.
 */
case class GitDiffHunk(file: String,
                       oldStart: Int,
                       newStart: Int,
                       lines: List[GitDiffLine])
  derives RW
