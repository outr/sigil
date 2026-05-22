package sigil.tool.model

import fabric.rw.*

/**
 * Typed result for [[sigil.tool.git.GitLogTool]].
 *
 *   - [[GitLogOutput.Listed]] — `commits` in newest-first order.
 *   - [[GitLogOutput.Failed]] — `git log` exited non-zero.
 */
enum GitLogOutput extends sigil.tool.ToolOutput derives RW {

  case Listed(commits: List[GitCommitEntry])

  case Failed(error: String, exitCode: Int)
}
