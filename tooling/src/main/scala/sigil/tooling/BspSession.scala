package sigil.tooling

import ch.epfl.scala.bsp4j.*
import org.eclipse.lsp4j.jsonrpc.Launcher
import rapid.Task

import java.io.File
import java.util.concurrent.{CompletableFuture, Executors}
import java.util.concurrent.atomic.AtomicLong
import scala.jdk.CollectionConverters.*

/**
 * Long-lived BSP client session — wraps one build-server subprocess
 * pinned to a project root. Same shape as [[LspSession]]; the
 * protocol differs only in vocabulary (build targets instead of
 * language documents).
 *
 * BSP sessions live longer than LSP sessions by default (sbt
 * warm-up is much heavier than Metals warm-up — minutes, not
 * seconds), so the idle window in [[BspBuildConfig]] defaults to
 * 30 minutes.
 */
final class BspSession(val config: BspBuildConfig,
                       process: Process,
                       server: BuildServer) {

  private val lastUseAt: AtomicLong = new AtomicLong(System.currentTimeMillis())

  def touch(): Unit = lastUseAt.set(System.currentTimeMillis())

  def idleSince: Long = lastUseAt.get()

  /** All build targets the server knows about. `Sigil` agents
    * typically fetch this once on connect and cache it; targets
    * change rarely (a new sub-project is added, etc.). */
  def workspaceBuildTargets: Task[List[BuildTarget]] = Task.defer {
    touch()
    BspSession.fromFuture(server.workspaceBuildTargets()).map(_.getTargets.asScala.toList)
  }

  /** Compile the given build targets. Returns the result `statusCode`
    * (`OK` / `ERROR` / `CANCELLED`) and any task notifications the
    * server published during the compile. Compile diagnostics arrive
    * via `build/publishDiagnostics` — apps that want them subscribe
    * through a custom client; the default client drops them. */
  def compile(targets: List[BuildTargetIdentifier]): Task[CompileResult] = Task.defer {
    touch()
    val params = new CompileParams(targets.asJava)
    BspSession.fromFuture(server.buildTargetCompile(params))
  }

  def shutdown(): Task[Unit] = Task {
    try { server.buildShutdown().get(2, java.util.concurrent.TimeUnit.SECONDS); () } catch { case _: Throwable => () }
    try { server.onBuildExit() } catch { case _: Throwable => () }
    try { process.destroy() } catch { case _: Throwable => () }
    if (process.isAlive) {
      process.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)
      if (process.isAlive) process.destroyForcibly()
    }
    ()
  }
}

object BspSession {

  /** Spawn a BSP server subprocess and run the `build/initialize`
    * handshake. The lsp4j JSON-RPC infrastructure handles the wire
    * layer; the typed proxy is `BuildServer & ScalaBuildServer` so
    * Scala-specific RPCs (`buildTarget/scalaMainClasses`,
    * `buildTarget/scalaTestClasses`) are reachable. */
  def spawn(config: BspBuildConfig): Task[BspSession] = Task.defer {
    val pb = new ProcessBuilder((config.command :: config.args).asJava)
    pb.directory(new File(config.projectRoot))
    pb.redirectErrorStream(false)
    config.env.foreach { case (k, v) => pb.environment().put(k, v) }
    val process = pb.start()

    val client = new SilentBuildClient
    val executor = Executors.newSingleThreadExecutor { r =>
      val t = new Thread(r, s"bsp-${config.projectRoot}")
      t.setDaemon(true)
      t
    }
    val launcher = new Launcher.Builder[BuildServer]()
      .setLocalService(client)
      .setRemoteInterface(classOf[BuildServer])
      .setInput(process.getInputStream)
      .setOutput(process.getOutputStream)
      .setExecutorService(executor)
      .create()
    val server = launcher.getRemoteProxy
    launcher.startListening()

    val initParams = new InitializeBuildParams(
      "sigil-tooling",
      sigilToolingVersion,
      "2.1.0",
      new File(config.projectRoot).toURI.toString,
      new BuildClientCapabilities(java.util.List.of("scala", "java"))
    )

    fromFuture(server.buildInitialize(initParams)).map { _ =>
      server.onBuildInitialized()
      new BspSession(config, process, server)
    }
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

  private def sigilToolingVersion: String = "1.0.0-SNAPSHOT"

  /** Build client that ignores every server-side notification. Sigil
    * agents query targets/compile/test on demand; we don't surface
    * the wire log. Apps that want it subclass and pass through
    * [[BspSession.spawn]]. */
  final class SilentBuildClient extends BuildClient {
    override def onBuildShowMessage(params: ShowMessageParams): Unit = ()

    override def onBuildLogMessage(params: LogMessageParams): Unit = ()

    override def onBuildTaskStart(params: TaskStartParams): Unit = ()

    override def onBuildTaskProgress(params: TaskProgressParams): Unit = ()

    override def onBuildTaskFinish(params: TaskFinishParams): Unit = ()

    override def onBuildPublishDiagnostics(params: PublishDiagnosticsParams): Unit = ()

    override def onBuildTargetDidChange(params: DidChangeBuildTarget): Unit = ()

    override def onRunPrintStdout(params: PrintParams): Unit = ()

    override def onRunPrintStderr(params: PrintParams): Unit = ()
  }
}
