package sigil.tool.fs

import rapid.{Stream, Task}
import sigil.TurnContext
import sigil.tool.model.BashInput
import sigil.tool.output.{Node, PaginatedTool}
import sigil.tool.{ToolExample, ToolName}

/**
 * Execute a shell command via the [[FileSystemContext]]. Paginated
 * output:
 *   - stdout lines surface as [[BashLine.Stdout]] nodes in arrival order
 *   - stderr lines as [[BashLine.Stderr]] nodes
 *   - the process's exit code lands as a single trailing [[BashLine.Exit]] node
 *
 * Apps that want sandboxing pass a `LocalFileSystemContext(basePath)`
 * that confines the command's working directory.
 */
final class BashTool(context: FileSystemContext) extends PaginatedTool[BashInput, BashLine](
  name = ToolName("bash"),
  description0 =
    """Execute a shell command (via `bash -c`). Optional `workingDir` sets the cwd; `timeoutMs` defaults
      |to 120 s. Output rows: stdout lines first (in arrival order), then stderr, then a single
      |Exit row carrying the exit code.""".stripMargin,
  examples = List(
    ToolExample("List a directory", BashInput(command = "ls -la /tmp")),
    ToolExample("Run a build with custom timeout", BashInput(command = "cargo build --release", timeoutMs = Some(600000L)))
  ),
  keywords = Set(
    "bash", "shell", "command", "exec", "run", "sh",
    "script", "terminal", "execute", "invoke", "system",
    "cli", "process", "spawn", "subprocess"
  )
) with sigil.tool.DestructiveExternalTool {
  // Bug #86 — generic primitive: ranks below domain-specific
  // tools when both match a query.
  override def preferIfNoBetter: Boolean = true

  override protected def executeStream(input: BashInput, ctx: TurnContext): Stream[Node[BashLine]] =
    Stream.force(
      WorkspacePathResolver.resolveOptional(ctx, input.workingDir).flatMap { dir =>
        context.executeCommand(input.command, dir, input.timeoutMs.getOrElse(120000L)).map { r =>
          val stdoutLines =
            if (r.stdout.isEmpty) Iterator.empty
            else r.stdout.split('\n').iterator.map(l => Node.leaf[BashLine](BashLine.Stdout(l)))
          val stderrLines =
            if (r.stderr.isEmpty) Iterator.empty
            else r.stderr.split('\n').iterator.map(l => Node.leaf[BashLine](BashLine.Stderr(l)))
          val exitNode = Iterator.single(Node.leaf[BashLine](BashLine.Exit(r.exitCode)))
          Stream.fromIterator(Task.pure(stdoutLines ++ stderrLines ++ exitNode))
        }
      }
    )
}
