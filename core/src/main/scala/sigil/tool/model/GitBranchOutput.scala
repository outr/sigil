package sigil.tool.model

import fabric.rw.*

/**
 * Typed result for [[sigil.tool.git.GitBranchTool]].
 *
 *   - [[GitBranchOutput.Listed]] — `current` is the checked-out
 *     branch name; `branches` is every local (and, when requested,
 *     remote-tracking) ref.
 *   - [[GitBranchOutput.Failed]] — a `git` invocation exited non-zero.
 */
enum GitBranchOutput extends sigil.tool.ToolOutput derives RW {

  case Listed(current: String, branches: List[GitBranchEntry])

  case Failed(error: String, exitCode: Int)
}
