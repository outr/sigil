package sigil.tool.fs

import fabric.{num, obj, str}
import rapid.Stream
import sigil.TurnContext
import sigil.event.Event
import sigil.tool.model.BashInput
import sigil.tool.{ToolExample, ToolName, TypedTool}

/**
 * Execute a shell command via the [[FileSystemContext]]. Result
 * event carries `stdout`, `stderr`, and `exitCode`.
 *
 * Apps that want sandboxing pass a `LocalFileSystemContext(basePath)`
 * that confines the command's working directory.
 */
final class BashTool(context: FileSystemContext)
  extends TypedTool[BashInput](
    name = ToolName("bash"),
    description =
      """Execute a shell command (via `bash -c`). Optional `workingDir` sets the cwd; `timeoutMs` defaults to
        |120 s. Returns `stdout`, `stderr`, and `exitCode`. Output is truncated to ~100KB per stream.""".stripMargin,
    examples = List(
      ToolExample("List a directory", BashInput(command = "ls -la /tmp")),
      ToolExample("Run a build with custom timeout", BashInput(command = "cargo build --release", timeoutMs = Some(600000L)))
    ),
    keywords = Set("bash", "shell", "command", "exec", "run", "sh")
  ) {
  override protected def executeTyped(input: BashInput, ctx: TurnContext): Stream[Event] = Stream.force(
    context.executeCommand(input.command, input.workingDir, input.timeoutMs.getOrElse(120000L)).map { r =>
      val payload = obj("stdout" -> str(r.stdout), "stderr" -> str(r.stderr), "exitCode" -> num(r.exitCode))
      Stream.emit[Event](FsToolEmit(payload, ctx))
    }
  )
}
