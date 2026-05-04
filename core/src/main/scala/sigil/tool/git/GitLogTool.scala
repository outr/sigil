package sigil.tool.git

import fabric.{num, obj, str}
import rapid.Stream
import sigil.TurnContext
import sigil.event.Event
import sigil.tool.fs.{FileSystemContext, FsToolEmit, WorkspacePathResolver}
import sigil.tool.model.GitLogInput
import sigil.tool.{ToolExample, ToolName, TypedTool}

/**
 * Read-only `git_log` — runs `git log` with a record-separator
 * pretty-format so subjects / bodies containing newlines or pipes
 * survive parsing. Returns `{commits: [{sha, author, date, subject,
 * body?}]}`.
 */
final class GitLogTool(context: FileSystemContext)
  extends TypedTool[GitLogInput](
    name = ToolName("git_log"),
    description =
      """Recent commit history. Optional `path` filters to commits touching that path; `since` accepts any
        |git-date expression (`"2 weeks ago"`, `"2026-04-01"`); `limit` defaults to 20. Set `includeBody`
        |to true for the full commit body. Returns `{commits: [{sha, author, date, subject, body?}]}`.""".stripMargin,
    examples = List(
      ToolExample("20 most recent commits",     GitLogInput()),
      ToolExample("Last 5 commits on a path",   GitLogInput(path = Some("src/main"), limit = Some(5))),
      ToolExample("Commits since last Friday",  GitLogInput(since = Some("last friday"), includeBody = true))
    ),
    keywords = Set("git", "log", "history", "commits", "blame")
  ) {
  override protected def executeTyped(input: GitLogInput, ctx: TurnContext): Stream[Event] = Stream.force(
    WorkspacePathResolver.resolveOptional(ctx, input.workingDir).flatMap { dir =>
      val limit  = input.limit.getOrElse(20)
      val format = "%H%x00%an%x00%aI%x00%s%x00%b%x1e"
      val sinceArg = input.since.fold("")(s => s" --since=${shellQuote(s)}")
      val pathArg  = input.path.fold("")(p => s" -- ${shellQuote(p)}")
      val cmd = s"""git log --pretty=format:$format -n $limit$sinceArg$pathArg"""
      context.executeCommand(cmd, dir).map { r =>
        val payload =
          if (r.exitCode != 0)
            obj("error" -> str(r.stderr), "exitCode" -> num(r.exitCode))
          else GitOps.parseLog(r.stdout, input.includeBody)
        Stream.emit[Event](FsToolEmit(payload, ctx))
      }
    }
  )

  private def shellQuote(s: String): String = "'" + s.replace("'", "'\\''") + "'"
}
