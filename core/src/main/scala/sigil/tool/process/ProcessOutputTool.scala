package sigil.tool.process

import rapid.Task
import sigil.TurnContext
import sigil.tool.model.{ProcessOutputInput, ProcessOutputResult, ProcessRunStatus}
import sigil.tool.{ToolExample, ToolName, TypedOutputTool}

/**
 * Read accumulated stdout/stderr from a registered subprocess.
 * `sinceCursor` is the previous read's `nextCursor` — agents call
 * with cursor 0 first, then pass `nextCursor` forward. Optional
 * `waitForLines` / `waitForPattern` block (briefly, capped by
 * `waitTimeoutMs`) until the subprocess emits something useful.
 */
final class ProcessOutputTool(registry: ProcessRegistry)
  extends TypedOutputTool[ProcessOutputInput, ProcessOutputResult](
    name = ToolName("process_output"),
    description =
      """Read new stdout/stderr from a registered subprocess. Returns the new bytes plus a monotonic
        |cursor — pass the previous `nextCursor` to read only new bytes. `dropped: true` means the
        |requested cursor predates the buffer's earliest retained byte (the agent missed some output).
        |Optional `waitForLines` / `waitForPattern` block until a condition or `waitTimeoutMs` expires.""".stripMargin,
    examples = List(
      ToolExample("First read on a new handle",                 ProcessOutputInput(handle = "p1")),
      ToolExample("Delta read after the previous cursor",       ProcessOutputInput(handle = "p1", sinceCursor = 4096L)),
      ToolExample("Block up to 5 s for the next 'compiled' line", ProcessOutputInput(handle = "p1", waitForPattern = Some("compiled"), waitTimeoutMs = 5000L))
    ),
    keywords = Set("process", "output", "stdout", "stderr", "tail", "watch", "stream")
  ) {
  override def paginate: Boolean = false

  override protected def executeTyped(input: ProcessOutputInput, ctx: TurnContext): Task[ProcessOutputResult] =
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
      ProcessOutputResult(
        handle      = result.handle,
        stdout      = result.stdout,
        stderr      = result.stderr,
        sinceCursor = result.sinceCursor,
        nextCursor  = result.nextCursor,
        status      = status,
        exitCode    = result.exitCode,
        dropped     = result.dropped
      )
    }
}
