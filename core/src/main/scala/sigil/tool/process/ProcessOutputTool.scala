package sigil.tool.process

import fabric.rw.*
import rapid.Task
import sigil.tool.ToolContext
import sigil.tool.model.{ProcessOutputInput, ProcessOutputResult, ProcessRunStatus}
import sigil.tool.{Tool, ToolExample, ToolName, ToolResult}

/**
 * Read accumulated stdout/stderr from a registered subprocess.
 * `sinceCursor` is the previous read's `nextCursor` — agents call
 * with cursor 0 first, then pass `nextCursor` forward. Optional
 * `waitForLines` / `waitForPattern` block (briefly, capped by
 * `waitTimeoutMs`) until the subprocess emits something useful.
 */
final class ProcessOutputTool(registry: ProcessRegistry) extends Tool {
  type Input  = ProcessOutputInput
  type Output = ProcessOutputResult
  val inputRW  = summon[RW[ProcessOutputInput]]
  val outputRW = summon[RW[ProcessOutputResult]]

  val name = ToolName("process_output")
  val description =
    """Read new stdout/stderr from a registered subprocess. Returns the new bytes plus a monotonic
      |cursor — pass the previous `nextCursor` to read only new bytes. `dropped: true` means the
      |requested cursor predates the buffer's earliest retained byte (the agent missed some output).
      |Optional `waitForLines` / `waitForPattern` block until a condition or `waitTimeoutMs` expires.""".stripMargin
  override val examples = List(
    ToolExample("First read on a new handle",                 ProcessOutputInput(handle = "p1")),
    ToolExample("Delta read after the previous cursor",       ProcessOutputInput(handle = "p1", sinceCursor = 4096L)),
    ToolExample("Block up to 5 s for the next 'compiled' line", ProcessOutputInput(handle = "p1", waitForPattern = Some("compiled"), waitTimeoutMs = 5000L))
  )
  override val keywords = Set("process", "output", "stdout", "stderr", "tail", "watch", "stream")

  override def executeResult(input: ProcessOutputInput, ctx: ToolContext): Task[ToolResult[ProcessOutputResult]] =
    if (looksLikePath(input.handle))
      Task.pure(ToolResult.failure(
        message = s"'${input.handle}' is a file path, not a process handle. process_output only reads " +
          "subprocesses started by process_spawn. To read a tool-output file (e.g. under .sigil/output/), " +
          "use read_file with offset/limit, or grep it for a pattern.",
        hint = Some("Externalized tool results are recovered with read_file / grep, not process_output.")
      ))
    else
      registry.output(
        handle         = input.handle,
        sinceCursor    = input.sinceCursor,
        waitForLines   = input.waitForLines,
        waitForPattern = input.waitForPattern,
        waitTimeoutMs  = input.waitTimeoutMs
      ).map { result =>
        val status = result.status match {
          case ProcessStatus.Running   => ProcessRunStatus.Running
          case ProcessStatus.Exited(_) => ProcessRunStatus.Exited
        }
        ToolResult.success(ProcessOutputResult(
          handle      = result.handle,
          stdout      = result.stdout,
          stderr      = result.stderr,
          sinceCursor = result.sinceCursor,
          nextCursor  = result.nextCursor,
          status      = status,
          exitCode    = result.exitCode,
          dropped     = result.dropped
        ))
      }.handleError {
        case _: NoSuchElementException =>
          registry.list(None).map { handles =>
            val active = handles.map(_.id)
            ToolResult.failure(
              message = s"No process handle '${input.handle}'. " +
                (if (active.isEmpty) "No subprocesses are currently registered."
                 else s"Active handles: ${active.mkString(", ")}.") +
                " Spawn one with process_spawn, or — if this is an externalized tool-output file — read it with read_file.",
              hint = Some("process_output handles come from process_spawn, not from .sigil/output file paths.")
            )
          }
        case other => Task.error(other)
      }

  /** A real process handle is a short token from `process_spawn` (e.g. "p1");
    * anything containing path separators or a file extension is a file path the
    * agent confused for a handle — most often a `.sigil/output/...` overflow
    * file. Redirect those to `read_file` rather than failing opaquely (#370). */
  private def looksLikePath(handle: String): Boolean =
    handle.contains("/") || handle.contains("\\") || handle.contains(".sigil") || handle.endsWith(".txt")
}
