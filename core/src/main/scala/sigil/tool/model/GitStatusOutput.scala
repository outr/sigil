package sigil.tool.model

import fabric.rw.*

/**
 * Typed result for [[sigil.tool.git.GitStatusTool]].
 *
 *   - [[GitStatusOutput.Reported]] — the working tree was inspected;
 *     `branch` / `ahead` / `behind` describe HEAD's relationship to
 *     its upstream and `entries` lists every change.
 *   - [[GitStatusOutput.Failed]] — `git status` exited non-zero
 *     (not a repo, permission denied, etc.); `error` is git's stderr
 *     and `exitCode` the process exit code.
 */
enum GitStatusOutput extends sigil.tool.ToolOutput derives RW {

  case Reported(branch: String,
                ahead: Int,
                behind: Int,
                entries: List[GitStatusEntry])

  case Failed(error: String, exitCode: Int)
}
