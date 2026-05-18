package sigil.tool.git

import fabric.{num, obj, str}
import rapid.Stream
import sigil.TurnContext
import sigil.event.Event
import sigil.tool.fs.{FileSystemContext, FsToolEmit, WorkspacePathResolver}
import sigil.tool.model.GitDiffInput
import sigil.tool.{ToolExample, ToolName, TypedTool}

/**
 * Read-only `git_diff` — runs `git diff` (or `git diff --staged`).
 * Returns the unified-diff text by default; pass `format = "hunks"`
 * for a structured `{hunks: [{file, oldStart, newStart, lines:
 * [{kind, text}]}]}` payload.
 */
final class GitDiffTool(context: FileSystemContext)
  extends TypedTool[GitDiffInput](
    name = ToolName("git_diff"),
    description =
      """Show unstaged changes (default) or staged changes (`staged: true`). Optional `path` restricts the diff to
        |a file/directory. `format = "text"` (default) returns raw unified diff; `format = "hunks"` returns
        |structured hunks: `{hunks: [{file, oldStart, newStart, lines: [{kind, text}]}]}`.""".stripMargin,
    examples = List(
      ToolExample("Unstaged changes", GitDiffInput()),
      ToolExample("Staged changes for a single file", GitDiffInput(path = Some("README.md"), staged = true)),
      ToolExample("Structured hunks", GitDiffInput(format = "hunks"))
    ),
    keywords = Set("git", "diff", "changes", "patch", "hunk")
  )
  with sigil.tool.ReadOnlyExternalTool {
  override def paginate: Boolean = false

  override protected def executeTyped(input: GitDiffInput, ctx: TurnContext): Stream[Event] = Stream.force(
    WorkspacePathResolver.resolveOptional(ctx, input.workingDir).flatMap { dir =>
      val stagedFlag = if (input.staged) " --staged" else ""
      val pathArg = input.path.fold("")(p => s" -- $p")
      val cmd = s"git diff$stagedFlag$pathArg"
      context.executeCommand(cmd, dir).map { r =>
        val payload =
          if (r.exitCode != 0)
            obj("error" -> str(r.stderr), "exitCode" -> num(r.exitCode))
          else if (input.format == "hunks") GitOps.parseDiff(r.stdout)
          else obj("text" -> str(r.stdout))
        Stream.emit[Event](FsToolEmit(payload, ctx))
      }
    }
  )
}
