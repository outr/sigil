package sigil.debug

import fabric.rw.*
import rapid.{Stream, Task}
import sigil.TurnContext
import sigil.event.Event
import sigil.tool.{ToolExample, ToolInput, ToolName, TypedTool}

case class DapSessionStatusInput(sessionId: String,
                                 waitForStopMs: Long = 0L,
                                 drainOutput: Boolean = true) extends ToolInput derives RW

/**
 * Snapshot the current state of a debug session — running vs.
 * stopped, latest stop reason / thread, captured stdout/stderr,
 * terminated flag, exit code (if exited).
 *
 * `waitForStopMs > 0` blocks until the program reaches a stop event
 * or the timeout expires — useful as the "wait for breakpoint hit"
 * loop after a `dap_continue` / `dap_step_*`. `drainOutput = true`
 * (default) consumes the queued output lines so subsequent calls
 * see only fresh output.
 */
final class DapSessionStatusTool(val manager: DapManager) extends TypedTool[DapSessionStatusInput](
  name = ToolName("dap_session_status"),
  description =
    """Snapshot a debug session's state — running / stopped / terminated, output, etc.
      |
      |`sessionId` selects the active session.
      |`waitForStopMs` (default 0) — when > 0, blocks until the next stop event or timeout.
      |`drainOutput` (default true) — consume captured stdout/stderr (so next call sees only new output).""".stripMargin,
  examples = List(
    ToolExample(
      "wait up to 5 seconds for the next stop event",
      DapSessionStatusInput(sessionId = "demo-session", waitForStopMs = 5000)
    )
  )
) with DapToolSupport {
  override protected def executeTyped(input: DapSessionStatusInput, context: TurnContext): Stream[Event] =
    withSession(input.sessionId, context) { session =>
      val waitTask =
        if (input.waitForStopMs <= 0) Task.unit
        else waitForStopOrTimeout(session, System.currentTimeMillis() + input.waitForStopMs)

      waitTask.map { _ =>
        val client = session.client
        val sb = new StringBuilder
        sb.append(s"Session '${session.sessionId}' (${session.config.languageId})\n")
        sb.append(s"  initialized=${client.initializedFlag.get()}\n")
        sb.append(s"  terminated=${client.terminated.get()}\n")
        client.lastExited.get().foreach { ev =>
          sb.append(s"  exitCode=${ev.getExitCode}\n")
        }
        client.lastStopped.get() match {
          case Some(stop) =>
            val reason = Option(stop.getReason).getOrElse("unknown")
            val thread = Option(stop.getThreadId).map(_.toString).getOrElse("?")
            val desc = Option(stop.getDescription).map(d => s" — $d").getOrElse("")
            sb.append(s"  stopped: thread=$thread reason=$reason$desc\n")
          case None =>
            if (!client.terminated.get()) sb.append(s"  state=running\n")
            else sb.append(s"  state=terminated\n")
        }
        client.lastBreakpoint.get().foreach { bp =>
          sb.append(s"  lastBreakpoint=${bp.getReason}\n")
        }
        if (input.drainOutput) {
          val out = client.drainOutput()
          if (out.nonEmpty) {
            sb.append("--- output ---\n")
            out.foreach { o =>
              val cat = Option(o.getCategory).getOrElse("stdout")
              sb.append(s"[$cat] ${o.getOutput}")
            }
          }
        }
        sb.toString
      }
    }

  private def waitForStopOrTimeout(session: DapSession, deadline: Long): Task[Unit] = Task.defer {
    val client = session.client
    if (client.lastStopped.get().isDefined || client.terminated.get() || System.currentTimeMillis() > deadline)
      Task.unit
    else
      Task.sleep(scala.concurrent.duration.FiniteDuration(100, "millis")).flatMap(_ => waitForStopOrTimeout(session, deadline))
  }
}
