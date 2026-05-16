package sigil.tool.process

import fabric.{Json, Null, bool, num, obj, str}
import rapid.Stream
import sigil.TurnContext
import sigil.event.Event
import sigil.tool.fs.FsToolEmit
import sigil.tool.model.ProcessOutputInput
import sigil.tool.{ToolExample, ToolName, TypedTool}

/**
 * Read accumulated stdout/stderr from a registered subprocess.
 * `sinceCursor` is the previous read's `nextCursor` — agents call
 * with cursor 0 first, then pass `nextCursor` forward. Optional
 * `waitForLines` / `waitForPattern` block (briefly, capped by
 * `waitTimeoutMs`) until the subprocess emits something useful.
 */
final class ProcessOutputTool(registry: ProcessRegistry)
  extends TypedTool[ProcessOutputInput](
    name = ToolName("process_output"),
    description =
      """Read new stdout/stderr from a registered subprocess. Returns `{handle, stdout, stderr,
        |sinceCursor, nextCursor, status, exitCode?, dropped}`. Cursor is monotonic — pass the
        |previous `nextCursor` to read only new bytes. `dropped: true` means the requested cursor
        |predates the buffer's earliest retained byte (the agent missed some output). Optional
        |`waitForLines` / `waitForPattern` block until a condition or `waitTimeoutMs` expires.""".stripMargin,
    examples = List(
      ToolExample("First read on a new handle",                 ProcessOutputInput(handle = "p1")),
      ToolExample("Delta read after the previous cursor",       ProcessOutputInput(handle = "p1", sinceCursor = 4096L)),
      ToolExample("Block up to 5 s for the next 'compiled' line", ProcessOutputInput(handle = "p1", waitForPattern = Some("compiled"), waitTimeoutMs = 5000L))
    ),
    keywords = Set("process", "output", "stdout", "stderr", "tail", "watch", "stream")
  ) {
  override def paginate: Boolean = false

  override protected def executeTyped(input: ProcessOutputInput, ctx: TurnContext): Stream[Event] = Stream.force(
    registry.output(
      handle         = input.handle,
      sinceCursor    = input.sinceCursor,
      waitForLines   = input.waitForLines,
      waitForPattern = input.waitForPattern,
      waitTimeoutMs  = input.waitTimeoutMs
    ).map { result =>
      val statusStr = result.status match {
        case ProcessStatus.Running   => "running"
        case ProcessStatus.Exited(_) => "exited"
      }
      val payload = obj(
        "handle"      -> str(result.handle),
        "stdout"      -> str(result.stdout),
        "stderr"      -> str(result.stderr),
        "sinceCursor" -> num(result.sinceCursor),
        "nextCursor"  -> num(result.nextCursor),
        "status"      -> str(statusStr),
        "exitCode"    -> result.exitCode.fold[Json](Null)(c => num(c)),
        "dropped"     -> bool(result.dropped)
      )
      Stream.emit[Event](FsToolEmit(payload, ctx))
    }
  )
}
