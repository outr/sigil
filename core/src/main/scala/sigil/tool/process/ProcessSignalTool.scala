package sigil.tool.process

import fabric.rw.*
import rapid.Task
import sigil.tool.ToolContext
import sigil.tool.model.{ProcessSignalInput, ProcessSignalOutput}
import sigil.tool.{Tool, ToolExample, ToolName, ToolResult}

/**
 * Send a signal to a registered subprocess. Default `terminate`
 * sends SIGTERM, waits for the registry's grace period, then
 * SIGKILL if the child didn't exit. `kill` skips straight to
 * SIGKILL.
 */
final class ProcessSignalTool(registry: ProcessRegistry) extends Tool {
  type Input  = ProcessSignalInput
  type Output = ProcessSignalOutput
  val inputRW  = summon[RW[ProcessSignalInput]]
  val outputRW = summon[RW[ProcessSignalOutput]]

  val name = ToolName("process_signal")
  val description =
    """Send a signal to a subprocess. `signal` is one of `terminate` (default — graceful SIGTERM
      |then SIGKILL on grace timeout), `interrupt` (SIGINT-equivalent), `kill` (SIGKILL).
      |Returns the handle, the delivered signal, and whether delivery succeeded.""".stripMargin
  override val examples = List(
    ToolExample("Terminate gracefully",  ProcessSignalInput(handle = "p1")),
    ToolExample("Force-kill a hung proc", ProcessSignalInput(handle = "p1", signal = sigil.tool.model.ProcessSignal.Kill))
  )
  override val keywords = Set("process", "signal", "terminate", "kill", "stop")

  override def executeResult(input: ProcessSignalInput, ctx: ToolContext): Task[ToolResult[ProcessSignalOutput]] =
    registry.signal(input.handle, input.signal).map {
      case true =>
        ToolResult.success(ProcessSignalOutput(handle = input.handle, signal = input.signal, delivered = true))
      case false =>
        ToolResult.failure(
          message = s"Process '${input.handle}' was already dead; no ${input.signal} signal was delivered.",
          hint = Some("The process has already exited — read its final output with process_output, then stop tracking the handle.")
        )
    }.handleError {
      case _: NoSuchElementException =>
        registry.list(None).map { handles =>
          val active = handles.map(_.id)
          ToolResult.failure(
            message = s"No process handle '${input.handle}'. " +
              (if (active.isEmpty) "No subprocesses are currently registered."
               else s"Active handles: ${active.mkString(", ")}.") +
              " Spawn one with process_spawn before signaling it.",
            hint = Some("process_signal handles come from process_spawn, not from file paths.")
          )
        }
      case other => Task.error(other)
    }
}
