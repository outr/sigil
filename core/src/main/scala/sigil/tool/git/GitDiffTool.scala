package sigil.tool.git

import fabric.rw.*
import rapid.Task
import sigil.tool.ToolContext
import sigil.tool.fs.{FileSystemContext, WorkspacePathResolver}
import sigil.tool.model.{GitDiffFormat, GitDiffInput, GitDiffOutput}
import sigil.tool.{DiscoverySpec, Effect, Freshness, Tool, ToolExample, ToolName, ToolProfile, ToolSpec}

/**
 * Read-only `git_diff` — runs `git diff` (or `git diff --staged`).
 * Returns the unified-diff text by default; pass `format = "hunks"`
 * for a structured [[GitDiffOutput.Hunks]] payload.
 */
final class GitDiffTool(context: FileSystemContext) extends Tool {
  type Input  = GitDiffInput
  type Output = GitDiffOutput
  val inputRW  = summon[RW[GitDiffInput]]
  val outputRW = summon[RW[GitDiffOutput]]
  override val name = ToolName("git_diff")
  override val description =
    """Show unstaged changes (default) or staged changes (`staged: true`). Optional `path` restricts the diff to
      |a file/directory. `format = "text"` (default) returns raw unified diff; `format = "hunks"` returns
      |structured per-file hunks (oldStart / newStart line numbers plus classified context / add / remove lines).""".stripMargin
  val spec: ToolSpec = ToolSpec(
    name = name,
    description = description,
    profile = ToolProfile(effect = Effect.ReadOnly(Freshness.Stable)),
    discovery = DiscoverySpec(keywords = Set("git", "diff", "changes", "patch", "hunk"))
  )
  override val examples = List(
    ToolExample("Unstaged changes", GitDiffInput()),
    ToolExample("Staged changes for a single file", GitDiffInput(path = Some("README.md"), staged = true)),
    ToolExample("Structured hunks", GitDiffInput(format = GitDiffFormat.Hunks))
  )

  override def executeOutput(input: GitDiffInput, ctx: ToolContext): Task[GitDiffOutput] =
    WorkspacePathResolver.resolveOptional(ctx, input.workingDir).flatMap { dir =>
      val stagedFlag = if (input.staged) " --staged" else ""
      val pathArg    = input.path.fold("")(p => s" -- $p")
      val cmd        = s"git diff$stagedFlag$pathArg"
      context.executeCommand(cmd, dir).map { r =>
        if (r.exitCode != 0) GitDiffOutput.Failed(r.stderr, r.exitCode)
        else input.format match {
          case GitDiffFormat.Hunks => GitDiffOutput.Hunks(GitOps.parseDiff(r.stdout))
          case GitDiffFormat.Text  => GitDiffOutput.Text(r.stdout)
        }
      }
    }
}
