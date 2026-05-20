package sigil.tool.model

import fabric.rw.*

/**
 * Typed result for [[sigil.tool.git.GitDiffTool]].
 *
 *   - [[GitDiffOutput.Text]] — raw unified-diff text (the default
 *     `format`).
 *   - [[GitDiffOutput.Hunks]] — structured per-file hunks (requested
 *     via `format = GitDiffFormat.Hunks`).
 *   - [[GitDiffOutput.Failed]] — `git diff` exited non-zero.
 */
enum GitDiffOutput derives RW {

  case Text(text: String)

  case Hunks(hunks: List[GitDiffHunk])

  case Failed(error: String, exitCode: Int)
}
