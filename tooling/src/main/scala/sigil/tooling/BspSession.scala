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
 *
 * Notifications from the server (diagnostics, log messages,
 * task progress, run-target stdout/stderr) accumulate in
 * [[client]]; tools snapshot them between calls.
 */
final class BspSession(val config: BspBuildConfig,
                       process: Process,
                       server: CombinedBuildServer,
                       val client: BspRecordingBuildClient) {

  private val lastUseAt: AtomicLong = new AtomicLong(System.currentTimeMillis())

  def touch(): Unit = lastUseAt.set(System.currentTimeMillis())
  def idleSince: Long = lastUseAt.get()

  // ---- target discovery ----

  def workspaceBuildTargets: Task[List[BuildTarget]] = Task.defer {
    touch()
    BspSession.fromFuture(server.workspaceBuildTargets()).map(_.getTargets.asScala.toList)
  }

  def reload: Task[Unit] = Task.defer {
    touch()
    BspSession.fromFuture(server.workspaceReload()).map(_ => ())
  }

  def sources(targets: List[BuildTargetIdentifier]): Task[List[SourcesItem]] = Task.defer {
    touch()
    BspSession.fromFuture(server.buildTargetSources(new SourcesParams(targets.asJava))).map(_.getItems.asScala.toList)
  }

  def inverseSources(uri: String): Task[List[BuildTargetIdentifier]] = Task.defer {
    touch()
    val td = new TextDocumentIdentifier(uri)
    BspSession.fromFuture(server.buildTargetInverseSources(new InverseSourcesParams(td))).map { result =>
      Option(result.getTargets).map(_.asScala.toList).getOrElse(Nil)
    }
  }

  def dependencySources(targets: List[BuildTargetIdentifier]): Task[List[DependencySourcesItem]] = Task.defer {
    touch()
    BspSession.fromFuture(server.buildTargetDependencySources(new DependencySourcesParams(targets.asJava)))
      .map(_.getItems.asScala.toList)
  }

  def dependencyModules(targets: List[BuildTargetIdentifier]): Task[List[DependencyModulesItem]] = Task.defer {
    touch()
    BspSession.fromFuture(server.buildTargetDependencyModules(new DependencyModulesParams(targets.asJava)))
      .map(_.getItems.asScala.toList)
  }

  def resources(targets: List[BuildTargetIdentifier]): Task[List[ResourcesItem]] = Task.defer {
    touch()
    BspSession.fromFuture(server.buildTargetResources(new ResourcesParams(targets.asJava)))
      .map(_.getItems.asScala.toList)
  }

  def outputPaths(targets: List[BuildTargetIdentifier]): Task[List[OutputPathsItem]] = Task.defer {
    touch()
    BspSession.fromFuture(server.buildTargetOutputPaths(new OutputPathsParams(targets.asJava)))
      .map(_.getItems.asScala.toList)
  }

  // ---- build / test / run ----

  def compile(targets: List[BuildTargetIdentifier]): Task[CompileResult] = Task.defer {
    touch()
    BspSession.fromFuture(server.buildTargetCompile(new CompileParams(targets.asJava)))
  }

  def test(targets: List[BuildTargetIdentifier],
           arguments: List[String] = Nil): Task[TestResult] = Task.defer {
    touch()
    val params = new TestParams(targets.asJava)
    if (arguments.nonEmpty) params.setArguments(arguments.asJava)
    BspSession.fromFuture(server.buildTargetTest(params))
  }

  def run(target: BuildTargetIdentifier,
          arguments: List[String] = Nil): Task[RunResult] = Task.defer {
    touch()
    val params = new RunParams(target)
    if (arguments.nonEmpty) params.setArguments(arguments.asJava)
    BspSession.fromFuture(server.buildTargetRun(params))
  }

  def cleanCache(targets: List[BuildTargetIdentifier]): Task[CleanCacheResult] = Task.defer {
    touch()
    BspSession.fromFuture(server.buildTargetCleanCache(new CleanCacheParams(targets.asJava)))
  }

  // ---- Scala-specific ----

  def scalacOptions(targets: List[BuildTargetIdentifier]): Task[List[ScalacOptionsItem]] = Task.defer {
    touch()
    BspSession.fromFuture(server.buildTargetScalacOptions(new ScalacOptionsParams(targets.asJava)))
      .map(_.getItems.asScala.toList)
  }

  // The Scala-classes RPCs are deprecated in BSP in favor of
  // `buildTarget/jvmTestEnvironment` + `buildTarget/jvmRunEnvironment`
  // — but sbt / Bloop still ship them, so apps targeting either need
  // the legacy path. Suppress the deprecation locally; we expose the
  // JVM-env replacements alongside.
  @annotation.nowarn("cat=deprecation")
  def scalaTestClasses(targets: List[BuildTargetIdentifier]): Task[List[ScalaTestClassesItem]] = Task.defer {
    touch()
    BspSession.fromFuture(server.buildTargetScalaTestClasses(new ScalaTestClassesParams(targets.asJava)))
      .map(_.getItems.asScala.toList)
  }

  @annotation.nowarn("cat=deprecation")
  def scalaMainClasses(targets: List[BuildTargetIdentifier]): Task[List[ScalaMainClassesItem]] = Task.defer {
    touch()
    BspSession.fromFuture(server.buildTargetScalaMainClasses(new ScalaMainClassesParams(targets.asJava)))
      .map(_.getItems.asScala.toList)
  }

  // ---- JVM environment (modern replacement; not all servers support yet) ----

  def jvmTestEnvironment(targets: List[BuildTargetIdentifier]): Task[List[JvmEnvironmentItem]] = Task.defer {
    touch()
    BspSession.fromFuture(server.buildTargetJvmTestEnvironment(new JvmTestEnvironmentParams(targets.asJava)))
      .map(_.getItems.asScala.toList)
  }

  def jvmRunEnvironment(targets: List[BuildTargetIdentifier]): Task[List[JvmEnvironmentItem]] = Task.defer {
    touch()
    BspSession.fromFuture(server.buildTargetJvmRunEnvironment(new JvmRunEnvironmentParams(targets.asJava)))
      .map(_.getItems.asScala.toList)
  }

  // ---- shutdown ----

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
    * handshake. The server proxy is typed as [[CombinedBuildServer]]
    * so both the BSP base contract and the Scala / JVM extensions
    * are reachable. */
  def spawn(config: BspBuildConfig,
            client: BspRecordingBuildClient = new BspRecordingBuildClient): Task[BspSession] = Task.defer {
    val pb = new ProcessBuilder((config.command :: config.args).asJava)
    pb.directory(new File(config.projectRoot))
    pb.redirectErrorStream(false)
    config.env.foreach { case (k, v) => pb.environment().put(k, v) }
    val process = pb.start()

    val executor = Executors.newSingleThreadExecutor { r =>
      val t = new Thread(r, s"bsp-${config.projectRoot}")
      t.setDaemon(true)
      t
    }
    val launcher = new Launcher.Builder[CombinedBuildServer]()
      .setLocalService(client)
      .setRemoteInterface(classOf[CombinedBuildServer])
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
      new BspSession(config, process, server, client)
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
}
