package sigil.tool.process

import fabric.rw.*
import rapid.Task
import sigil.TurnContext
import sigil.tool.model.{ProcessSignalInput, ProcessSignalOutput}
import sigil.tool.{Tool, ToolExample, ToolName}

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

  override def executeOutput(input: ProcessSignalInput, ctx: TurnContext): Task[ProcessSignalOutput] =
    registry.signal(input.handle, input.signal).map { delivered =>
      ProcessSignalOutput(handle = input.handle, signal = input.signal, delivered = delivered)
    }
}
