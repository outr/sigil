package sigil.tool.git

import fabric.{num, obj, str}
import rapid.Stream
import sigil.TurnContext
import sigil.event.Event
import sigil.tool.fs.{FileSystemContext, FsToolEmit, WorkspacePathResolver}
import sigil.tool.model.GitBranchInput
import sigil.tool.{ToolExample, ToolName, TypedTool}

/**
 * Read-only `git_branch` — list local (and optionally remote)
 * branches plus identify the current branch. Returns
 * `{current, branches: [{name, sha, isCurrent, isRemote, tracking?}]}`.
 */
final class GitBranchTool(context: FileSystemContext)
  extends TypedTool[GitBranchInput](
    name = ToolName("git_branch"),
    description =
      """List branches. `includeRemotes` extends the listing with remote-tracking refs. Returns
        |`{current, branches: [{name, sha, isCurrent, isRemote, tracking?}]}`.""".stripMargin,
    examples = List(
      ToolExample("Local branches",            GitBranchInput()),
      ToolExample("Local + remote-tracking",   GitBranchInput(includeRemotes = true))
    ),
    keywords = Set("git", "branch", "branches", "checkout")
  ) with sigil.tool.ReadOnlyExternalTool {
  override protected def executeTyped(input: GitBranchInput, ctx: TurnContext): Stream[Event] = Stream.force(
    WorkspacePathResolver.resolveOptional(ctx, input.workingDir).flatMap { dir =>
      val flag = if (input.includeRemotes) "-a" else ""
      for {
        branchResult <- context.executeCommand(s"git branch $flag -vv", dir)
        currentResult <- context.executeCommand("git rev-parse --abbrev-ref HEAD", dir)
      } yield {
        val payload =
          if (branchResult.exitCode != 0 || currentResult.exitCode != 0)
            obj(
              "error"    -> str(if (branchResult.stderr.nonEmpty) branchResult.stderr else currentResult.stderr),
              "exitCode" -> num(if (branchResult.exitCode != 0) branchResult.exitCode else currentResult.exitCode)
            )
          else GitOps.parseBranches(branchResult.stdout, currentResult.stdout, input.includeRemotes)
        Stream.emit[Event](FsToolEmit(payload, ctx))
      }
    }
  )
}
