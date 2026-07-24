package sigil.tool.fs

import fabric.rw.*
import rapid.Task
import sigil.tool.ToolContext
import sigil.tool.model.BashInput
import sigil.tool.{PlaceholderInputDetector, TextToolOutput, Tool, ToolExample, ToolName, ToolResult}

/**
 * Execute a shell command via the [[FileSystemContext]]. Returns the
 * command's stdout, then stderr (when non-empty), then the exit code —
 * as plain text straight into the agent's context. A genuinely large
 * output is written to a file by the framework's overflow path (the
 * agent reads it with `grep` / `read_file`).
 *
 * Apps that want sandboxing pass a `LocalFileSystemContext(basePath)`
 * that confines the command's working directory.
 */
final class BashTool(context: FileSystemContext) extends Tool with sigil.tool.DestructiveExternalTool {
  type Input = BashInput
  type Output = TextToolOutput
  val inputRW = summon[RW[BashInput]]
  val outputRW = summon[RW[TextToolOutput]]

  val name = ToolName("bash")
  val description =
    """Execute a shell command (via `bash -c`). Optional `workingDir` sets the cwd; `timeoutMs` defaults
      |to 120 s. Returns stdout, then stderr (if any), then an `[exit: N]` line.""".stripMargin

  override val examples: List[ToolExample] = List(
    ToolExample("List a directory", BashInput(command = "ls -la /tmp")),
    ToolExample("Run a build with custom timeout", BashInput(command = "cargo build --release", timeoutMs = Some(600000L)))
  )

  override val keywords: Set[String] = Set(
    "bash",
    "shell",
    "command",
    "exec",
    "run",
    "sh",
    "script",
    "terminal",
    "execute",
    "invoke",
    "system",
    "cli",
    "process",
    "spawn",
    "subprocess"
  )

  override def preferIfNoBetter: Boolean = true

  override def executeResult(input: BashInput, ctx: ToolContext): Task[ToolResult[TextToolOutput]] =
    PlaceholderInputDetector.validateNoPlaceholders("command" -> input.command) match {
      case Some(reason) => Task.pure(ToolResult.failure(reason))
      case None =>
        WorkspacePathResolver.resolveOptional(ctx, input.workingDir).flatMap { dir =>
          context.executeCommand(input.command, dir, input.timeoutMs.getOrElse(120000L))
            .map(r => ToolResult.Success(TextToolOutput(render(r))))
        }
    }

  private def render(r: CommandResult): String = {
    val out = new StringBuilder
    if (r.stdout.nonEmpty) out.append(r.stdout.stripLineEnd)
    if (r.stderr.nonEmpty) {
      if (out.nonEmpty) out.append("\n")
      out.append("[stderr]\n").append(r.stderr.stripLineEnd)
    }
    if (out.nonEmpty) out.append("\n")
    out.append(s"[exit: ${r.exitCode}]")
    out.toString
  }
}
