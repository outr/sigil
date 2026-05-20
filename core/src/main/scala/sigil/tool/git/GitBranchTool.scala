package sigil.tool.git

import rapid.Task
import sigil.TurnContext
import sigil.tool.fs.{FileSystemContext, WorkspacePathResolver}
import sigil.tool.model.{GitBranchInput, GitBranchOutput}
import sigil.tool.{ToolExample, ToolName, TypedOutputTool}

/**
 * Read-only `git_branch` — list local (and optionally remote)
 * branches plus identify the current branch. Returns a typed
 * [[GitBranchOutput]].
 */
final class GitBranchTool(context: FileSystemContext)
  extends TypedOutputTool[GitBranchInput, GitBranchOutput](
    name = ToolName("git_branch"),
    description =
      """List branches. `includeRemotes` extends the listing with remote-tracking refs. Returns the
        |current branch name plus every branch (name, sha, isCurrent, isRemote, tracking?).""".stripMargin,
    examples = List(
      ToolExample("Local branches",            GitBranchInput()),
      ToolExample("Local + remote-tracking",   GitBranchInput(includeRemotes = true))
    ),
    keywords = Set("git", "branch", "branches", "checkout")
  ) with sigil.tool.ReadOnlyExternalTool {
  override def paginate: Boolean = false

  override protected def executeTyped(input: GitBranchInput, ctx: TurnContext): Task[GitBranchOutput] =
    WorkspacePathResolver.resolveOptional(ctx, input.workingDir).flatMap { dir =>
      val flag = if (input.includeRemotes) "-a" else ""
      for {
        branchResult  <- context.executeCommand(s"git branch $flag -vv", dir)
        currentResult <- context.executeCommand("git rev-parse --abbrev-ref HEAD", dir)
      } yield {
        if (branchResult.exitCode != 0 || currentResult.exitCode != 0)
          GitBranchOutput.Failed(
            error    = if (branchResult.stderr.nonEmpty) branchResult.stderr else currentResult.stderr,
            exitCode = if (branchResult.exitCode != 0) branchResult.exitCode else currentResult.exitCode
          )
        else
          GitBranchOutput.Listed(
            current  = currentResult.stdout.trim,
            branches = GitOps.parseBranches(branchResult.stdout, input.includeRemotes)
          )
      }
    }
}
