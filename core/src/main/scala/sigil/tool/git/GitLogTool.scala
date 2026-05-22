package sigil.tool.git

import fabric.rw.*
import rapid.Task
import sigil.TurnContext
import sigil.tool.fs.{FileSystemContext, WorkspacePathResolver}
import sigil.tool.model.{GitLogInput, GitLogOutput}
import sigil.tool.{Tool, ToolExample, ToolName}

/**
 * Read-only `git_log` — runs `git log` with a record-separator
 * pretty-format so subjects / bodies containing newlines or pipes
 * survive parsing. Returns a typed [[GitLogOutput]].
 */
final class GitLogTool(context: FileSystemContext)
  extends Tool with sigil.tool.ReadOnlyExternalTool {
  type Input  = GitLogInput
  type Output = GitLogOutput
  val inputRW  = summon[RW[GitLogInput]]
  val outputRW = summon[RW[GitLogOutput]]
  val name = ToolName("git_log")
  val description =
    """Recent commit history. Optional `path` filters to commits touching that path; `since` accepts any
      |git-date expression (`"2 weeks ago"`, `"2026-04-01"`); `limit` defaults to 20. Set `includeBody`
      |to true for the full commit body. Returns a list of commits (sha, author, date, subject, body?).""".stripMargin
  override val examples = List(
    ToolExample("20 most recent commits",    GitLogInput()),
    ToolExample("Last 5 commits on a path",  GitLogInput(path = Some("src/main"), limit = Some(5))),
    ToolExample("Commits since last Friday", GitLogInput(since = Some("last friday"), includeBody = true))
  )
  override val keywords = Set("git", "log", "history", "commits", "blame")

  override def executeOutput(input: GitLogInput, ctx: TurnContext): Task[GitLogOutput] =
    WorkspacePathResolver.resolveOptional(ctx, input.workingDir).flatMap { dir =>
      val limit    = input.limit.getOrElse(20)
      val format   = "%H%x00%an%x00%aI%x00%s%x00%b%x1e"
      val sinceArg = input.since.fold("")(s => s" --since=${GitOps.shellQuote(s)}")
      val pathArg  = input.path.fold("")(p => s" -- ${GitOps.shellQuote(p)}")
      val cmd      = s"""git log --pretty=format:$format -n $limit$sinceArg$pathArg"""
      context.executeCommand(cmd, dir).map { r =>
        if (r.exitCode != 0) GitLogOutput.Failed(r.stderr, r.exitCode)
        else GitLogOutput.Listed(GitOps.parseLog(r.stdout, input.includeBody))
      }
    }
}
