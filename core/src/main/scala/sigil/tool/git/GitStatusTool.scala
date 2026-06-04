package sigil.tool.git

import fabric.rw.*
import rapid.Task
import sigil.tool.ToolContext
import sigil.tool.fs.{FileSystemContext, WorkspacePathResolver}
import sigil.tool.model.{GitStatusInput, GitStatusOutput}
import sigil.tool.{Tool, ToolExample, ToolName}

/**
 * Read-only `git_status` — runs `git status --porcelain=v1
 * --branch` against the conversation's workspace (or the supplied
 * `workingDir`) and returns a typed [[GitStatusOutput]]. Replaces the
 * agent's previous "shell out and regex `M  path`" workflow.
 */
final class GitStatusTool(context: FileSystemContext) extends Tool with sigil.tool.ReadOnlyExternalTool {
  type Input = GitStatusInput
  type Output = GitStatusOutput
  val inputRW = summon[RW[GitStatusInput]]
  val outputRW = summon[RW[GitStatusOutput]]
  val name = ToolName("git_status")
  val description =
    """Show working-tree status as a branch summary plus a list of changed entries.
      |Index/working state codes follow git porcelain (unmodified, modified, added, deleted,
      |renamed, copied, unmerged, untracked, ignored).""".stripMargin
  override val examples = List(
    ToolExample("Status of the conversation workspace", GitStatusInput()),
    ToolExample("Status of a specific repo", GitStatusInput(workingDir = Some("/abs/path/to/repo")))
  )
  override val keywords = Set("git", "status", "changes", "diff", "porcelain", "uncommitted")

  override def executeOutput(input: GitStatusInput, ctx: ToolContext): Task[GitStatusOutput] =
    WorkspacePathResolver.resolveOptional(ctx, input.workingDir).flatMap { dir =>
      context.executeCommand("git status --porcelain=v1 --branch", dir).map { r =>
        if (r.exitCode != 0) GitStatusOutput.Failed(r.stderr, r.exitCode)
        else {
          val (header, entries) = GitOps.parseStatus(r.stdout)
          GitStatusOutput.Reported(
            branch = header.branch,
            ahead = header.ahead,
            behind = header.behind,
            entries = entries
          )
        }
      }
    }
}
