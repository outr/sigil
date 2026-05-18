package sigil.tool.process

import fabric.{bool, obj, str}
import rapid.Stream
import sigil.TurnContext
import sigil.event.Event
import sigil.tool.fs.FsToolEmit
import sigil.tool.model.ProcessSignalInput
import sigil.tool.{ToolExample, ToolName, TypedTool}

/**
 * Send a signal to a registered subprocess. Default `terminate`
 * sends SIGTERM, waits for the registry's grace period, then
 * SIGKILL if the child didn't exit. `kill` skips straight to
 * SIGKILL.
 */
final class ProcessSignalTool(registry: ProcessRegistry)
  extends TypedTool[ProcessSignalInput](
    name = ToolName("process_signal"),
    description =
      """Send a signal to a subprocess. `signal` is one of `terminate` (default — graceful SIGTERM
        |then SIGKILL on grace timeout), `interrupt` (SIGINT-equivalent), `kill` (SIGKILL).
        |Returns `{handle, signal, ok}`.""".stripMargin,
    examples = List(
      ToolExample("Terminate gracefully", ProcessSignalInput(handle = "p1")),
      ToolExample("Force-kill a hung proc", ProcessSignalInput(handle = "p1", signal = "kill"))
    ),
    keywords = Set("process", "signal", "terminate", "kill", "stop")
  ) {
  override def paginate: Boolean = false

  override protected def executeTyped(input: ProcessSignalInput, ctx: TurnContext): Stream[Event] = Stream.force(
    registry.signal(input.handle, input.signal).map { ok =>
      val payload = obj(
        "handle" -> str(input.handle),
        "signal" -> str(input.signal),
        "ok" -> bool(ok)
      )
      Stream.emit[Event](FsToolEmit(payload, ctx))
    }
  )
}
