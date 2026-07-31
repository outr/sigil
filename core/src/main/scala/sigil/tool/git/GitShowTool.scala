package sigil.tool.git

import fabric.rw.*
import rapid.Task
import sigil.tool.ToolContext
import sigil.tool.fs.{FileSystemContext, WorkspacePathResolver}
import sigil.tool.model.{GitShowInput, GitShowOutput}
import sigil.tool.{DiscoverySpec, Effect, Freshness, Resolution, Tool, ToolExample, ToolIO, ToolName, ToolProfile, ToolSpec}

/**
 * Read-only `git_show` — render a single commit. Returns a typed
 * [[GitShowOutput]] carrying the commit metadata plus its diff as
 * structured hunks (the same shape as `git_diff format = "hunks"`).
 */
final class GitShowTool(context: FileSystemContext) extends Tool {
  type Input  = GitShowInput
  type Output = GitShowOutput
  val io: ToolIO[GitShowInput, GitShowOutput] = ToolIO.derived[GitShowInput, GitShowOutput].withExamples(
    ToolExample("Show HEAD",          GitShowInput(sha = "HEAD")),
    ToolExample("Show a specific sha", GitShowInput(sha = "abc1234"))
  )
  override val name = ToolName("git_show")
  override val description =
    """Show a single commit's metadata + diff. `sha` accepts any git revision spec (`HEAD`, `HEAD~1`,
      |a short sha, a tag). Returns the commit (sha, author, date, subject, body) plus structured hunks.""".stripMargin
  val spec: ToolSpec = ToolSpec(
    name = name,
    description = description,
    profile = ToolProfile(effect = Effect.ReadOnly(Freshness.Stable)),
    discovery = DiscoverySpec(keywords = Set("git", "show", "commit", "inspect"))
  )

  protected def resolve: Resolution[Input, Output] = Resolution.Simple(executeOutput)

  private def executeOutput(input: GitShowInput, ctx: ToolContext): Task[GitShowOutput] =
    WorkspacePathResolver.resolveOptional(ctx, input.workingDir).flatMap { dir =>
      val format = "%H%x00%an%x00%aI%x00%s%x00%b%x1e"
      // `--patch` is implicit for `git show`, but we add the
      // pretty-format header so subject / body parse cleanly off the
      // record separator before the diff body.
      val cmd = s"""git show --pretty=format:$format ${GitOps.shellQuote(input.sha)}"""
      context.executeCommand(cmd, dir).map { r =>
        if (r.exitCode != 0) GitShowOutput.Failed(r.stderr, r.exitCode)
        else parseShow(r.stdout)
      }
    }

  private def parseShow(stdout: String): GitShowOutput = stdout.split("\u001e", -1).toList match {
    case head :: tail =>
      val parts    = head.split("\u0000", -1).padTo(5, "")
      val diffPart = tail.headOption.getOrElse("")
      GitShowOutput.Found(
        sha     = parts(0),
        author  = parts(1),
        date    = parts(2),
        subject = parts(3),
        body    = parts(4),
        hunks   = GitOps.parseDiff(diffPart)
      )
    case Nil =>
      GitShowOutput.Found(sha = "", author = "", date = "", subject = "", body = "", hunks = Nil)
  }
}
