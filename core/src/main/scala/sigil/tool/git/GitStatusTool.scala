package sigil.tool.git

import fabric.{num, obj, str}
import rapid.Stream
import sigil.TurnContext
import sigil.event.Event
import sigil.tool.fs.{FileSystemContext, FsToolEmit, WorkspacePathResolver}
import sigil.tool.model.GitStatusInput
import sigil.tool.{ToolExample, ToolName, TypedTool}

/**
 * Read-only `git_status` — runs `git status --porcelain=v1
 * --branch` against the conversation's workspace (or the supplied
 * `workingDir`) and returns a typed `{branch, ahead, behind,
 * entries}` payload. Replaces the agent's previous "shell out and
 * regex `M  path`" workflow.
 */
final class GitStatusTool(context: FileSystemContext)
  extends TypedTool[GitStatusInput](
    name = ToolName("git_status"),
    description =
      """Show working-tree status as `{branch, ahead, behind, entries: [{path, indexState, workingState, renamedFrom?}]}`.
        |Index/working state codes follow git porcelain (` `, `M`, `A`, `D`, `R`, `C`, `?`, `!`).""".stripMargin,
    examples = List(
      ToolExample("Status of the conversation workspace", GitStatusInput()),
      ToolExample("Status of a specific repo", GitStatusInput(workingDir = Some("/abs/path/to/repo")))
    ),
    keywords = Set("git", "status", "changes", "diff", "porcelain", "uncommitted")
  )
  with sigil.tool.ReadOnlyExternalTool {
  override def paginate: Boolean = false

  override protected def executeTyped(input: GitStatusInput, ctx: TurnContext): Stream[Event] = Stream.force(
    WorkspacePathResolver.resolveOptional(ctx, input.workingDir).flatMap { dir =>
      context.executeCommand("git status --porcelain=v1 --branch", dir).map { r =>
        val payload =
          if (r.exitCode != 0)
            obj("error" -> str(r.stderr), "exitCode" -> num(r.exitCode))
          else GitOps.parseStatus(r.stdout)
        Stream.emit[Event](FsToolEmit(payload, ctx))
      }
    }
  )
}
