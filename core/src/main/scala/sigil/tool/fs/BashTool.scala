package sigil.tool.fs

import rapid.Task
import sigil.TurnContext
import sigil.tool.model.{BashInput, BashOutput}
import sigil.tool.{ToolExample, ToolName, TypedOutputTool}

/**
 * Execute a shell command via the [[FileSystemContext]]. Emits a
 * typed [[BashOutput]] carrying `stdout`, `stderr`, and `exitCode`
 * — agents pattern-match on the exit code or slice the streams
 * directly via [[TypedOutputTool.invoke]], no JSON parsing
 * required.
 *
 * Apps that want sandboxing pass a `LocalFileSystemContext(basePath)`
 * that confines the command's working directory.
 */
final class BashTool(context: FileSystemContext)
  extends TypedOutputTool[BashInput, BashOutput](
    name = ToolName("bash"),
    description =
      """Execute a shell command (via `bash -c`). Optional `workingDir` sets the cwd; `timeoutMs` defaults to
        |120 s. Returns `{stdout, stderr, exitCode}`. Output is truncated to ~100KB per stream.""".stripMargin,
    examples = List(
      ToolExample("List a directory", BashInput(command = "ls -la /tmp")),
      ToolExample("Run a build with custom timeout", BashInput(command = "cargo build --release", timeoutMs = Some(600000L)))
    ),
    keywords = Set("bash", "shell", "command", "exec", "run", "sh")
  ) {
  // Bug #86 — generic primitive: ranks below domain-specific
  // tools when both match a query.
  override def preferIfNoBetter: Boolean = true

  override protected def executeTyped(input: BashInput, ctx: TurnContext): Task[BashOutput] =
    // `workingDir` resolves against the conversation's workspace
    // when supplied; when omitted, the workspace itself becomes
    // the cwd so commands like `bash "ls"` operate on the right
    // project. Apps without a workspace fall through to JVM cwd
    // (existing behavior).
    WorkspacePathResolver.resolveOptional(ctx, input.workingDir).flatMap { dir =>
      context.executeCommand(input.command, dir, input.timeoutMs.getOrElse(120000L)).map { r =>
        BashOutput(stdout = r.stdout, stderr = r.stderr, exitCode = r.exitCode)
      }
    }

  /** Compact summary for the externalization path — show exit code
    * and a few lines of stdout. Most bash commands fit under the
    * threshold and ride inline; long builds spill to a StoredFile
    * with this summary visible to the agent. */
  override protected def summarize(out: BashOutput, jsonRendered: String): String = {
    val streamPreview = out.stdout.take(200).replaceAll("\n", " ").trim
    s"[bash exit=${out.exitCode}, stdout starts: ${streamPreview}…]"
  }
}
