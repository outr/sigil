package sigil.debug

import org.eclipse.lsp4j.debug.*
import org.eclipse.lsp4j.debug.launch.DSPLauncher
import org.eclipse.lsp4j.debug.services.IDebugProtocolServer
import rapid.Task

import java.io.File
import java.util.concurrent.{CompletableFuture, Executors}
import java.util.concurrent.atomic.AtomicLong
import scala.jdk.CollectionConverters.*

/**
 * Long-lived DAP client session — wraps one debug-adapter
 * subprocess. Spawned per agent-initiated session by [[DapManager]];
 * lives until disconnect or idle timeout.
 *
 * Sessions are stateful by nature — the program is mid-execution,
 * the agent reads stack frames, sets breakpoints, steps. The
 * session holds the wire layer; the [[client]] field accumulates
 * the events tools snapshot between calls.
 */
final class DapSession(val config: DebugAdapterConfig,
                       val sessionId: String,
                       process: Process,
                       server: IDebugProtocolServer,
                       val client: DapRecordingClient) {

  private val lastUseAt: AtomicLong = new AtomicLong(System.currentTimeMillis())

  def touch(): Unit = lastUseAt.set(System.currentTimeMillis())
  def idleSince: Long = lastUseAt.get()

  // ---- lifecycle ----

  /** Initialize the adapter — handshake. The framework calls this
    * once at spawn; tools shouldn't normally invoke it directly. */
  def initialize(adapterId: String = "sigil-debug"): Task[Capabilities] = Task.defer {
    touch()
    val args = new InitializeRequestArguments()
    args.setClientID("sigil-debug")
    args.setClientName("sigil")
    args.setAdapterID(adapterId)
    args.setLinesStartAt1(true)
    args.setColumnsStartAt1(true)
    args.setPathFormat("path")
    args.setSupportsVariableType(true)
    args.setSupportsRunInTerminalRequest(false)
    DapSession.fromFuture(server.initialize(args))
  }

  /** Start a fresh process for debugging. `arguments` is a free-form
    * map of adapter-specific keys (program path, cwd, environment,
    * etc.) — the agent supplies what the language adapter expects. */
  def launch(arguments: java.util.Map[String, Object]): Task[Unit] = Task.defer {
    touch()
    DapSession.fromFuture(server.launch(arguments)).map(_ => ())
  }

  /** Attach to a running process. */
  def attach(arguments: java.util.Map[String, Object]): Task[Unit] = Task.defer {
    touch()
    DapSession.fromFuture(server.attach(arguments)).map(_ => ())
  }

  /** Tell the adapter all setup (breakpoints, exception filters) is
    * done. Required before the program runs to its first natural
    * stop. */
  def configurationDone(): Task[Unit] = Task.defer {
    touch()
    DapSession.fromFuture(server.configurationDone(new ConfigurationDoneArguments())).map(_ => ())
  }

  def disconnect(terminateDebuggee: Boolean = false): Task[Unit] = Task.defer {
    touch()
    val args = new DisconnectArguments()
    args.setTerminateDebuggee(terminateDebuggee)
    DapSession.fromFuture(server.disconnect(args)).map(_ => ())
  }

  def terminate(): Task[Unit] = Task.defer {
    touch()
    DapSession.fromFuture(server.terminate(new TerminateArguments())).map(_ => ())
  }

  // ---- breakpoints ----

  def setBreakpoints(sourcePath: String,
                     lines: List[Int]): Task[List[Breakpoint]] = Task.defer {
    touch()
    val source = new Source()
    source.setPath(sourcePath)
    source.setName(new File(sourcePath).getName)
    val args = new SetBreakpointsArguments()
    args.setSource(source)
    args.setBreakpoints(lines.map { line =>
      val bp = new SourceBreakpoint()
      bp.setLine(line)
      bp
    }.toArray)
    DapSession.fromFuture(server.setBreakpoints(args)).map(r => r.getBreakpoints.toList)
  }

  def setExceptionBreakpoints(filters: List[String]): Task[List[Breakpoint]] = Task.defer {
    touch()
    val args = new SetExceptionBreakpointsArguments()
    args.setFilters(filters.toArray)
    DapSession.fromFuture(server.setExceptionBreakpoints(args)).map { r =>
      Option(r).flatMap(x => Option(x.getBreakpoints)).map(_.toList).getOrElse(Nil)
    }
  }

  // ---- execution control ----

  def continueExecution(threadId: Int): Task[Boolean] = Task.defer {
    touch()
    val args = new ContinueArguments()
    args.setThreadId(threadId)
    DapSession.fromFuture(server.continue_(args))
      .map(r => Option(r.getAllThreadsContinued).map(_.booleanValue).getOrElse(false))
  }

  def next(threadId: Int): Task[Unit] = Task.defer {
    touch()
    val args = new NextArguments()
    args.setThreadId(threadId)
    DapSession.fromFuture(server.next(args)).map(_ => ())
  }

  def stepIn(threadId: Int): Task[Unit] = Task.defer {
    touch()
    val args = new StepInArguments()
    args.setThreadId(threadId)
    DapSession.fromFuture(server.stepIn(args)).map(_ => ())
  }

  def stepOut(threadId: Int): Task[Unit] = Task.defer {
    touch()
    val args = new StepOutArguments()
    args.setThreadId(threadId)
    DapSession.fromFuture(server.stepOut(args)).map(_ => ())
  }

  def pause(threadId: Int): Task[Unit] = Task.defer {
    touch()
    val args = new PauseArguments()
    args.setThreadId(threadId)
    DapSession.fromFuture(server.pause(args)).map(_ => ())
  }

  // ---- inspection ----

  def threads: Task[List[Thread]] = Task.defer {
    touch()
    DapSession.fromFuture(server.threads()).map(_.getThreads.toList)
  }

  def stackTrace(threadId: Int, startFrame: Int = 0, levels: Int = 20): Task[List[StackFrame]] = Task.defer {
    touch()
    val args = new StackTraceArguments()
    args.setThreadId(threadId)
    args.setStartFrame(startFrame)
    args.setLevels(levels)
    DapSession.fromFuture(server.stackTrace(args)).map(_.getStackFrames.toList)
  }

  def scopes(frameId: Int): Task[List[Scope]] = Task.defer {
    touch()
    val args = new ScopesArguments()
    args.setFrameId(frameId)
    DapSession.fromFuture(server.scopes(args)).map(_.getScopes.toList)
  }

  def variables(variablesReference: Int): Task[List[Variable]] = Task.defer {
    touch()
    val args = new VariablesArguments()
    args.setVariablesReference(variablesReference)
    DapSession.fromFuture(server.variables(args)).map(_.getVariables.toList)
  }

  def evaluate(expression: String,
               frameId: Option[Int] = None,
               context: String = "repl"): Task[EvaluateResponse] = Task.defer {
    touch()
    val args = new EvaluateArguments()
    args.setExpression(expression)
    frameId.foreach(id => args.setFrameId(id))
    args.setContext(context)
    DapSession.fromFuture(server.evaluate(args))
  }

  // ---- shutdown ----

  def shutdown(): Task[Unit] = Task {
    try { server.disconnect(new DisconnectArguments()).get(2, java.util.concurrent.TimeUnit.SECONDS); () }
    catch { case _: Throwable => () }
    try { process.destroy() } catch { case _: Throwable => () }
    if (process.isAlive) {
      process.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)
      if (process.isAlive) process.destroyForcibly()
    }
    ()
  }
}

object DapSession {

  /** Spawn a debug adapter, run the `initialize` handshake, and
    * return a ready-to-use session. The agent then calls `launch` /
    * `attach` and proceeds through the DAP lifecycle. */
  def spawn(config: DebugAdapterConfig,
            sessionId: String,
            client: DapRecordingClient = new DapRecordingClient): Task[DapSession] = Task.defer {
    val pb = new ProcessBuilder((config.command :: config.args).asJava)
    pb.redirectErrorStream(false)
    config.env.foreach { case (k, v) => pb.environment().put(k, v) }
    val process = pb.start()

    val executor = Executors.newSingleThreadExecutor { r =>
      val t = new java.lang.Thread(r, s"dap-${config.languageId}-$sessionId")
      t.setDaemon(true)
      t
    }
    val launcher = DSPLauncher.createClientLauncher(client, process.getInputStream, process.getOutputStream, executor, null)
    val server = launcher.getRemoteProxy
    launcher.startListening()

    val session = new DapSession(config, sessionId, process, server, client)
    session.initialize(config.languageId).map(_ => session)
  }

  def fromFuture[T](future: CompletableFuture[T]): Task[T] = {
    val completable = Task.completable[T]
    future.whenComplete { (value, error) =>
      if (error != null) {
        val unwrapped = error match {
          case ce: java.util.concurrent.CompletionException if ce.getCause != null => ce.getCause
          case other                                                               => other
        }
        completable.failure(unwrapped)
      } else completable.success(value)
    }
    completable
  }
}
