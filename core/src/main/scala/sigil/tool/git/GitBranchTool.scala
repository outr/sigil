package sigil.tool.git

import fabric.rw.*
import rapid.Task
import sigil.tool.ToolContext
import sigil.tool.fs.{FileSystemContext, WorkspacePathResolver}
import sigil.tool.model.{GitBranchInput, GitBranchOutput}
import sigil.tool.{DiscoverySpec, Effect, Freshness, Resolution, Tool, ToolExample, ToolIO, ToolName, ToolProfile, ToolSpec}

/**
 * Read-only `git_branch` — list local (and optionally remote)
 * branches plus identify the current branch. Returns a typed
 * [[GitBranchOutput]].
 */
final class GitBranchTool(context: FileSystemContext) extends Tool {
  type Input = GitBranchInput
  type Output = GitBranchOutput
  val io: ToolIO[GitBranchInput, GitBranchOutput] = ToolIO.derived[GitBranchInput, GitBranchOutput].withExamples(
    ToolExample("Local branches", GitBranchInput()),
    ToolExample("Local + remote-tracking", GitBranchInput(includeRemotes = true))
  )
  override val name = ToolName("git_branch")
  override val description =
    """List branches. `includeRemotes` extends the listing with remote-tracking refs. Returns the
      |current branch name plus every branch (name, sha, isCurrent, isRemote, tracking?).""".stripMargin
  val spec: ToolSpec = ToolSpec(
    name = name,
    description = description,
    profile = ToolProfile(effect = Effect.ReadOnly(Freshness.Stable)),
    discovery = DiscoverySpec(keywords = Set("git", "branch", "branches", "checkout"))
  )

  protected def resolve: Resolution[Input, Output] = Resolution.Simple(executeOutput)

  private def executeOutput(input: GitBranchInput, ctx: ToolContext): Task[GitBranchOutput] =
    WorkspacePathResolver.resolveOptional(ctx, input.workingDir).flatMap { dir =>
      val flag = if (input.includeRemotes) "-a" else ""
      for {
        branchResult <- context.executeCommand(s"git branch $flag -vv", dir)
        currentResult <- context.executeCommand("git rev-parse --abbrev-ref HEAD", dir)
      } yield
        if (branchResult.exitCode != 0 || currentResult.exitCode != 0)
          GitBranchOutput.Failed(
            error = if (branchResult.stderr.nonEmpty) branchResult.stderr else currentResult.stderr,
            exitCode = if (branchResult.exitCode != 0) branchResult.exitCode else currentResult.exitCode
          )
        else
          GitBranchOutput.Listed(
            current = currentResult.stdout.trim,
            branches = GitOps.parseBranches(branchResult.stdout, input.includeRemotes)
          )
    }
}
