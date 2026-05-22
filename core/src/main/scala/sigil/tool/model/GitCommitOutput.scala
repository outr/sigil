package sigil.tool.model

import fabric.rw.*

/**
 * Typed result for [[sigil.tool.git.GitCommitTool]].
 *
 *   - [[GitCommitOutput.Committed]] — the commit landed; `sha` is the
 *     new HEAD and `message` echoes the commit message.
 *   - [[GitCommitOutput.Failed]] — staging or committing exited
 *     non-zero (nothing to commit, hook rejection, etc.).
 */
enum GitCommitOutput extends sigil.tool.ToolOutput derives RW {

  case Committed(sha: String, message: String)

  case Failed(error: String, exitCode: Int)
}
