package sigil.tool.model

import fabric.rw.*

/**
 * Typed result for [[sigil.tool.git.GitShowTool]].
 *
 *   - [[GitShowOutput.Found]] — the revision resolved; metadata plus
 *     the commit's diff as structured `hunks`.
 *   - [[GitShowOutput.Failed]] — `git show` exited non-zero (unknown
 *     revision, ambiguous spec, not a repo).
 */
enum GitShowOutput derives RW {

  case Found(sha: String,
             author: String,
             date: String,
             subject: String,
             body: String,
             hunks: List[GitDiffHunk])

  case Failed(error: String, exitCode: Int)
}
