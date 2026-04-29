package sigil.debug

import org.eclipse.lsp4j.debug.*
import org.eclipse.lsp4j.debug.services.IDebugProtocolClient

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.{AtomicBoolean, AtomicReference}

/**
 * `IDebugProtocolClient` impl that records the events the agent
 * needs to reason about a debug session: stopped state, output
 * lines, terminated/exited flags, the most-recent breakpoint
 * notification, and the `initialized` lifecycle marker.
 *
 * Tools snapshot these between calls; the session itself only owns
 * the wire layer.
 *
 * Thread safety: all fields are atomic / concurrent. The output
 * queue grows unbounded — apps that worry about long-running
 * sessions producing huge volumes of output subclass and trim.
 */
class DapRecordingClient extends IDebugProtocolClient {

  /** True once the adapter has sent `initialized`. The agent must
    * wait for this before sending `setBreakpoints` / `configurationDone`. */
  val initializedFlag: AtomicBoolean = new AtomicBoolean(false)

  /** True once the adapter has sent `terminated`. The session is
    * about to (or has) shut down. */
  val terminated: AtomicBoolean = new AtomicBoolean(false)

  /** Latest `stopped` event the adapter published. `None` while the
    * program is running or before the first stop. */
  val lastStopped: AtomicReference[Option[StoppedEventArguments]] = new AtomicReference(None)

  /** Latest `continued` event. */
  val lastContinued: AtomicReference[Option[ContinuedEventArguments]] = new AtomicReference(None)

  /** Latest `exited` event — carries the program's exit code. */
  val lastExited: AtomicReference[Option[ExitedEventArguments]] = new AtomicReference(None)

  /** Captured output lines from the debugged program. Drain via
    * [[drainOutput]]. */
  val output: ConcurrentLinkedQueue[OutputEventArguments] = new ConcurrentLinkedQueue()

  /** Latest `thread` event — tracks thread start/exit notifications. */
  val lastThread: AtomicReference[Option[ThreadEventArguments]] = new AtomicReference(None)

  /** Latest `breakpoint` notification. */
  val lastBreakpoint: AtomicReference[Option[BreakpointEventArguments]] = new AtomicReference(None)

  /** Drain captured output. Returns the events in arrival order. */
  def drainOutput(): List[OutputEventArguments] = {
    val out = scala.collection.mutable.ListBuffer.empty[OutputEventArguments]
    var next = output.poll()
    while (next != null) {
      out += next
      next = output.poll()
    }
    out.toList
  }

  // ---- IDebugProtocolClient overrides ----

  override def initialized(): Unit = {
    initializedFlag.set(true)
  }

  override def stopped(args: StoppedEventArguments): Unit = {
    lastStopped.set(Some(args))
  }

  override def continued(args: ContinuedEventArguments): Unit = {
    lastContinued.set(Some(args))
    // Consider the program running again — clear the stopped marker.
    lastStopped.set(None)
  }

  override def exited(args: ExitedEventArguments): Unit = {
    lastExited.set(Some(args))
  }

  override def terminated(args: TerminatedEventArguments): Unit = {
    terminated.set(true)
  }

  override def thread(args: ThreadEventArguments): Unit = {
    lastThread.set(Some(args))
  }

  override def output(args: OutputEventArguments): Unit = {
    output.offer(args); ()
  }

  override def breakpoint(args: BreakpointEventArguments): Unit = {
    lastBreakpoint.set(Some(args))
  }
}
