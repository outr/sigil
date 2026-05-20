package sigil.tool.model

import fabric.rw.*

/**
 * A single line within a [[GitDiffHunk]]. `kind` classifies the line
 * as context / add / remove; `text` is the line content with the
 * leading diff marker (` `, `+`, `-`) stripped.
 */
case class GitDiffLine(kind: GitDiffLineKind, text: String) derives RW
