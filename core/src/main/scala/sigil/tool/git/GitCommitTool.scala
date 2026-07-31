package sigil.tool.git

import fabric.rw.*
import rapid.Task
import sigil.tool.ToolContext
import sigil.tool.fs.{FileSystemContext, WorkspacePathResolver}
import sigil.tool.model.{GitCommitInput, GitCommitOutput}
import sigil.tool.{DiscoverySpec, Effect, MutationTargeting, Resolution, Tool, ToolExample, ToolIO, ToolName, ToolProfile, ToolSpec}

/**
 * `git_commit` — stage `paths` (or every tracked change when
 * omitted) and commit with `message`. Returns a typed
 * [[GitCommitOutput]]. WRITE tool — opt-in like `delete_file`; not in
 * the default `AllShippedTools` list. Apps that want commit authorship
 * register an instance explicitly.
 */
final class GitCommitTool(context: FileSystemContext) extends Tool {
  type Input  = GitCommitInput
  type Output = GitCommitOutput
  val io: ToolIO[GitCommitInput, GitCommitOutput] = ToolIO.derived[GitCommitInput, GitCommitOutput].withExamples(
    ToolExample("Commit all tracked changes", GitCommitInput(message = "Fix typo")),
    ToolExample("Commit specific paths",      GitCommitInput(message = "Add config", paths = Some(List("config/app.yaml"))))
  )
  override val name = ToolName("git_commit")
  override val description =
    """Stage `paths` (or all tracked changes when omitted) and create a commit. Returns the new
      |commit sha on success or a structured failure. WRITES — apps that want this exposed
      |register it on top of `AllShippedTools`.""".stripMargin
  val spec: ToolSpec = ToolSpec(
    name = name,
    description = description,
    profile = ToolProfile(effect = Effect.Destructive(target = MutationTargeting.none, consequence = "DESTRUCTIVE.")),
    discovery = DiscoverySpec(keywords = Set("git", "commit", "save", "checkpoint"))
  )

  protected def resolve: Resolution[Input, Output] = Resolution.Simple(executeOutput)

  private def executeOutput(input: GitCommitInput, ctx: ToolContext): Task[GitCommitOutput] =
    WorkspacePathResolver.resolveOptional(ctx, input.workingDir).flatMap { dir =>
      val pathsToStage = input.paths.getOrElse(Nil)
      val addCmd = pathsToStage match {
        case Nil   => "git add -u"
        case paths => "git add -- " + paths.map(GitOps.shellQuote).mkString(" ")
      }
      val emptyFlag = if (input.allowEmpty) " --allow-empty" else ""
      val commitCmd = s"""git commit$emptyFlag -m ${GitOps.shellQuote(input.message)}"""
      val shaCmd    = "git rev-parse HEAD"

      for {
        addResult <- context.executeCommand(addCmd, dir)
        commitResult <-
          if (addResult.exitCode != 0) Task.pure(addResult)
          else context.executeCommand(commitCmd, dir)
        shaResult <-
          if (commitResult.exitCode != 0) Task.pure(commitResult)
          else context.executeCommand(shaCmd, dir)
      } yield {
        if (commitResult.exitCode != 0 || shaResult.exitCode != 0)
          GitCommitOutput.Failed(
            error    = if (commitResult.stderr.nonEmpty) commitResult.stderr else shaResult.stderr,
            exitCode = if (commitResult.exitCode != 0) commitResult.exitCode else shaResult.exitCode
          )
        else
          GitCommitOutput.Committed(sha = shaResult.stdout.trim, message = input.message)
      }
    }
}
