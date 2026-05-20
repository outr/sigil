package sigil.tool.model

import fabric.rw.*

/**
 * Typed result for [[sigil.tool.git.GitPushTool]].
 *
 *   - [[GitPushOutput.Pushed]] — the push succeeded; `output` /
 *     `stderr` carry git's progress reporting (git writes ref-update
 *     progress to stderr even on success).
 *   - [[GitPushOutput.Failed]] — the push failed or was gate-blocked;
 *     `error` is the classified reason, `detail` a human-readable
 *     message, `exitCode` git's exit code when the push shelled out
 *     (`None` when the force-push gate short-circuited).
 */
enum GitPushOutput derives RW {

  case Pushed(output: String, stderr: String)

  case Failed(error: GitPushError,
              detail: String,
              exitCode: Option[Int] = None,
              stderr: Option[String] = None)
}
