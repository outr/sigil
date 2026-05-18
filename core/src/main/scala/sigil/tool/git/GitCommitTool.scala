package sigil.tool.git

import fabric.{bool, num, obj, str}
import rapid.Stream
import sigil.TurnContext
import sigil.event.Event
import sigil.tool.fs.{FileSystemContext, FsToolEmit, WorkspacePathResolver}
import sigil.tool.model.GitCommitInput
import sigil.tool.{ToolExample, ToolName, TypedTool}

/**
 * `git_commit` — stage `paths` (or every tracked change when
 * omitted) and commit with `message`. Returns `{success, sha,
 * message}`. WRITE tool — opt-in like `delete_file`; not in the
 * default `AllShippedTools` list. Apps that want commit authorship
 * register an instance explicitly.
 */
final class GitCommitTool(context: FileSystemContext)
  extends TypedTool[GitCommitInput](
    name = ToolName("git_commit"),
    description =
      """Stage `paths` (or all tracked changes when omitted) and create a commit. Returns
        |`{success, sha?, message?, error?}`. WRITES — apps that want this exposed register
        |it on top of `AllShippedTools`.""".stripMargin,
    examples = List(
      ToolExample("Commit all tracked changes", GitCommitInput(message = "Fix typo")),
      ToolExample("Commit specific paths", GitCommitInput(message = "Add config", paths = Some(List("config/app.yaml"))))
    ),
    keywords = Set("git", "commit", "save", "checkpoint")
  )
  with sigil.tool.DestructiveExternalTool {
  override def paginate: Boolean = false

  override protected def executeTyped(input: GitCommitInput, ctx: TurnContext): Stream[Event] = Stream.force(
    WorkspacePathResolver.resolveOptional(ctx, input.workingDir).flatMap { dir =>
      val pathsToStage = input.paths.getOrElse(Nil)
      val addCmd = pathsToStage match {
        case Nil => "git add -u"
        case paths => "git add -- " + paths.map(shellQuote).mkString(" ")
      }
      val emptyFlag = if (input.allowEmpty) " --allow-empty" else ""
      val commitCmd = s"""git commit$emptyFlag -m ${shellQuote(input.message)}"""
      val shaCmd = "git rev-parse HEAD"

      for {
        addResult <- context.executeCommand(addCmd, dir)
        commitResult <-
          if (addResult.exitCode != 0) rapid.Task.pure(addResult)
          else context.executeCommand(commitCmd, dir)
        shaResult <-
          if (commitResult.exitCode != 0) rapid.Task.pure(commitResult)
          else context.executeCommand(shaCmd, dir)
      } yield {
        val payload =
          if (commitResult.exitCode != 0 || shaResult.exitCode != 0)
            obj(
              "success" -> bool(false),
              "error" -> str(if (commitResult.stderr.nonEmpty) commitResult.stderr else shaResult.stderr),
              "exitCode" -> num(if (commitResult.exitCode != 0) commitResult.exitCode else shaResult.exitCode)
            )
          else
            obj(
              "success" -> bool(true),
              "sha" -> str(shaResult.stdout.trim),
              "message" -> str(input.message)
            )
        Stream.emit[Event](FsToolEmit(payload, ctx))
      }
    }
  )

  private def shellQuote(s: String): String = "'" + s.replace("'", "'\\''") + "'"
}
