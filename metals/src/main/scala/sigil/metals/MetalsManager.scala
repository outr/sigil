package sigil.metals

import lightdb.time.Timestamp
import org.eclipse.lsp4j.{ClientCapabilities, InitializeParams, InitializedParams, WindowClientCapabilities, WorkspaceClientCapabilities, WorkspaceFolder}
import org.eclipse.lsp4j.jsonrpc.Launcher
import org.eclipse.lsp4j.services.{LanguageClient, LanguageServer}
import rapid.Task
import sigil.Sigil
import sigil.db.SigilDB
import sigil.mcp.{McpCollections, McpServerConfig, McpTransport}
import spice.net.{TLDValidation, URL}

import java.io.File
import java.nio.file.{Files, Path}
import java.util.concurrent.{ConcurrentHashMap, Executors}
import java.util.concurrent.atomic.{AtomicBoolean, AtomicReference}
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/**
 * Lifecycle owner for spawned Metals subprocesses. One Metals per
 * canonical workspace path; multiple conversations resolving to the
 * same path share the running subprocess.
 *
 * Responsibilities:
 *
 *   - **Spawn.** [[ensureRunning]] launches Metals (via
 *     [[MetalsSigil.metalsLauncher]]) under the workspace as cwd if
 *     no live process exists. Polls `.metals/mcp.json` for the chosen
 *     MCP endpoint; once present, inserts an [[McpServerConfig]] in
 *     `db.mcpServers` so [[sigil.mcp.McpManager]] picks the connection
 *     up through the existing flow.
 *
 *   - **Port-churn handling.** The watcher fiber re-reads
 *     `.metals/mcp.json` on every tick. If the endpoint changes
 *     (Metals restarted with a new port), the manager updates the
 *     `McpServerConfig.transport` in the DB and calls
 *     `mcpManager.closeClient(name)` so the next tool use forces a
 *     reconnect against the fresh endpoint.
 *
 *   - **Idle reaping.** A separate sweeper fiber tears down
 *     subprocesses that haven't been [[touch]]-ed inside
 *     [[MetalsSigil.metalsIdleTimeoutMs]]. Lazy respawn on next
 *     [[ensureRunning]] call.
 *
 *   - **Shutdown.** [[shutdown]] kills every spawned subprocess and
 *     removes the corresponding `McpServerConfig` rows. Called from
 *     [[MetalsSigil.onShutdown]] for orderly teardown; a JVM
 *     shutdown hook covers the catastrophic case.
 *
 * Apps don't construct this directly — [[MetalsSigil]] holds it as a
 * lazy val. Public API is small: [[ensureRunning]], [[stop]],
 * [[status]], [[touch]], [[shutdown]].
 */
final class MetalsManager(host: MetalsSigil) {

  /** Per-workspace state. `workspaceKey` is the
    * [[MetalsWorkspaceKey]]-derived `metals-<hash>` server name.
    * `stderrDrainer` is the thread tailing the subprocess's stderr
    * (LSP wire is on stdout; logs / fatal errors come on stderr) so
    * failure-path diagnostics surface real subprocess output.
    * `onLogLine` is the per-line callback supplied by the caller —
    * typically [[StartMetalsTool]] threading `TurnContext.toolLog` —
    * so streaming Metals output reaches the chat chip via
    * [[sigil.event.ToolLog]] events (bug #69). */
  private final case class Entry(workspace: Path,
                                 workspaceKey: String,
                                 process: Process,
                                 @volatile var endpoint: Option[String],
                                 @volatile var lastUsedMs: Long,
                                 stderrDrainer: Thread,
                                 onLogLine: AtomicReference[Option[String => Task[Unit]]])

  /** Keyed by canonical workspace path string. */
  private val entries: ConcurrentHashMap[String, Entry] = new ConcurrentHashMap()

  private val started: AtomicBoolean = new AtomicBoolean(false)
  private val stopped: AtomicBoolean = new AtomicBoolean(false)

  /** Watcher / reaper poll cadence. Also drives the spawn-rendezvous
    * loop's idle pulse. */
  private val PollIntervalMs: Long = 200L

  /**
   * Ensure a Metals subprocess is running for `workspace` and the
   * matching [[McpServerConfig]] is registered. Idempotent — a
   * second call against the same workspace is a no-op apart from
   * touching the last-used clock so the reaper doesn't sweep the
   * subprocess between calls.
   *
   * Returns the server name [[sigil.mcp.McpManager]] consumers
   * reference (`metals-<hash>`).
   */
  def ensureRunning(workspace: Path,
                    onLogLine: Option[String => Task[Unit]] = None): Task[String] = Task.defer {
    val canonical = workspace.toAbsolutePath.normalize
    val key = canonical.toString
    val name = MetalsWorkspaceKey.of(canonical)
    val existing = Option(entries.get(key))
    existing match {
      case Some(entry) if entry.process.isAlive =>
        // Idempotent re-attach — update the line callback so a
        // second start_metals call routes Metals output to the new
        // ToolInvoke's chip instead of stranded against the
        // original.
        entry.lastUsedMs = System.currentTimeMillis()
        entry.onLogLine.set(onLogLine)
        Task.pure(entry.workspaceKey)
      case _ =>
        // No live entry — spawn and wait for rendezvous, then
        // upsert the McpServerConfig.
        spawnAndRegister(canonical, key, name, onLogLine)
    }
  }

  /** Stop the Metals subprocess for `workspace` and remove its
    * server config. No-op when nothing is running. Returns true if
    * a subprocess was actually torn down. */
  def stop(workspace: Path): Task[Boolean] = Task.defer {
    val canonical = workspace.toAbsolutePath.normalize.toString
    Option(entries.remove(canonical)) match {
      case None => Task.pure(false)
      case Some(entry) =>
        teardownEntry(entry).map(_ => true)
    }
  }

  /** Snapshot of every active Metals subprocess. Used by
    * [[MetalsStatusTool]] to render the chip. */
  def status: Task[List[MetalsManager.WorkspaceStatus]] = Task {
    import scala.jdk.CollectionConverters.*
    entries.values().asScala.iterator.map { e =>
      MetalsManager.WorkspaceStatus(
        workspace    = e.workspace,
        workspaceKey = e.workspaceKey,
        endpoint     = e.endpoint,
        alive        = e.process.isAlive,
        lastUsedMs   = e.lastUsedMs
      )
    }.toList
  }

  /** Mark a workspace as recently used so the reaper postpones
    * tearing it down. Called by app code on every Metals-touching
    * tool use; the [[ensureRunning]] / [[stop]] paths touch
    * automatically. */
  def touch(workspace: Path): Unit = {
    val canonical = workspace.toAbsolutePath.normalize.toString
    Option(entries.get(canonical)).foreach(_.lastUsedMs = System.currentTimeMillis())
  }

  /** Tear down every spawned subprocess and remove every server
    * config the manager owns. Called from [[MetalsSigil.onShutdown]]
    * for orderly teardown. Idempotent. */
  def shutdown: Task[Unit] = Task.defer {
    if (!stopped.compareAndSet(false, true)) Task.unit
    else {
      import scala.jdk.CollectionConverters.*
      val all = entries.values().asScala.toList
      entries.clear()
      Task.sequence(all.map(teardownEntry)).unit
    }
  }

  // ---- internals ----

  private def spawnAndRegister(workspace: Path,
                                key: String,
                                name: String,
                                onLogLine: Option[String => Task[Unit]]): Task[String] = Task.defer {
    if (!Files.isDirectory(workspace)) {
      Task.error(new IllegalArgumentException(
        s"MetalsManager: workspace $workspace is not a directory"
      ))
    } else {
      ensureBackgroundFibers()
      // Bug #68 — wrap startup in a `runAsFrameworkWorkflow` so the
      // activity bar shows what's happening (replaces the silent
      // 30s deadline with subprocess-monitored progress). The
      // workflow's CancellationToken kills the subprocess if a
      // user invokes `cancel_framework_workflow`.
      host.runAsFrameworkWorkflow(
        workflowType   = "metals-startup",
        label          = s"Starting Metals for ${workspace.getFileName}",
        conversationId = None
      ) { control =>
        Task.defer {
          val pb = new ProcessBuilder(host.metalsLauncher*)
            .directory(workspace.toFile)
            // Keep stdout/stderr separate — Metals' LSP wire is on
            // stdout; human logs / fatals go on stderr. Merging
            // them would corrupt the LSP frame parsing.
            .redirectErrorStream(false)
          val processOpt = scala.util.Try(pb.start()).toEither
          processOpt match {
            case Left(t) =>
              Task.error(new RuntimeException(
                s"MetalsManager: failed to launch Metals (${host.metalsLauncher.mkString(" ")}) for $workspace: ${t.getMessage}",
                t
              ))
            case Right(process) =>
              val nowMs = System.currentTimeMillis()
              val tail = new java.util.concurrent.LinkedBlockingDeque[String](OutputTailLimit)
              val onLogLineRef: AtomicReference[Option[String => Task[Unit]]] =
                new AtomicReference(onLogLine)
              // Subprocess stderr → tail buffer for failure
              // diagnostics + ToolLog fan-out. Stdout is owned by
              // the LSP launcher.
              val stderrDrainer = startStderrDrainer(process, name, tail, onLogLineRef)

              val entry = Entry(
                workspace     = workspace,
                workspaceKey  = name,
                process       = process,
                endpoint      = None,
                lastUsedMs    = nowMs,
                stderrDrainer = stderrDrainer,
                onLogLine     = onLogLineRef
              )
              entries.put(key, entry)

              // Bug #70 — drive the LSP handshake via lsp4j's
              // Launcher. Metals only does work after `initialize`
              // + `initialized` land; bug #70 was that we'd
              // spawned the subprocess without ever sending them.
              startLspLauncher(entry, name, onLogLineRef).flatMap { _ =>
                control.step("Metals LSP initialize complete; waiting for endpoint")
                  .flatMap(_ => waitForReady(entry, control, tail))
              }
          }
        }
      }
    }
  }

  /** Build + start the lsp4j Launcher against the subprocess's
    * stdin/stdout, send `initialize` + `initialized`, and wait for
    * Metals to return its server capabilities before letting the
    * caller proceed to poll for `.metals/mcp.json`. */
  private def startLspLauncher(entry: Entry,
                                label: String,
                                onLogLine: AtomicReference[Option[String => Task[Unit]]]): Task[Unit] =
    Task.defer {
      val client: LanguageClient = host.metalsLanguageClient(label, onLogLine)
      val executor = Executors.newSingleThreadExecutor { r =>
        val t = new Thread(r, s"sigil-metals-lsp-$label")
        t.setDaemon(true)
        t
      }
      val launcher = new Launcher.Builder[LanguageServer]()
        .setLocalService(client)
        .setRemoteInterface(classOf[LanguageServer])
        .setInput(entry.process.getInputStream)
        .setOutput(entry.process.getOutputStream)
        .setExecutorService(executor)
        .create()
      launcher.startListening()
      val server = launcher.getRemoteProxy

      val initParams = new InitializeParams()
      initParams.setProcessId(ProcessHandle.current().pid().toInt)
      val rootUri = entry.workspace.toAbsolutePath.normalize.toUri.toString
      val folder = new WorkspaceFolder(rootUri, entry.workspace.getFileName.toString)
      initParams.setWorkspaceFolders(java.util.Collections.singletonList(folder))
      initParams.setCapabilities(buildClientCapabilities())

      // Fire the initialize request without blocking the main task.
      // Real Metals takes <1s to settle but a non-LSP-aware test
      // fixture may never respond — blocking here would freeze the
      // spec until the deadline. Per LSP spec we should wait for
      // the response before sending `initialized`, so we chain via
      // CompletableFuture; for fixtures that don't respond, the
      // `initialized` notification is simply skipped (they don't
      // care anyway because they write mcp.json directly).
      Task {
        server.initialize(initParams).whenComplete { (result, error) =>
          if (error != null) {
            scribe.warn(s"MetalsManager: LSP initialize to $label failed: ${error.getMessage}")
          } else {
            val name = Option(result).flatMap(r => Option(r.getServerInfo)).map(_.getName).getOrElse("?")
            scribe.info(s"[$label] LSP initialize OK (server=$name)")
            try server.initialized(new InitializedParams())
            catch { case t: Throwable => scribe.warn(s"[$label] initialized notification failed: ${t.getMessage}") }
          }
        }
        ()
      }
    }

  private def buildClientCapabilities(): ClientCapabilities = {
    val caps = new ClientCapabilities()
    val window = new WindowClientCapabilities()
    window.setWorkDoneProgress(true)
    caps.setWindow(window)
    val workspace = new WorkspaceClientCapabilities()
    // Tell Metals we'll respond to `workspace/configuration` —
    // that's what carries `start-mcp-server: true` so Metals
    // boots its MCP server and writes `.metals/mcp.json`.
    workspace.setConfiguration(true)
    caps.setWorkspace(workspace)
    caps
  }

  /** Bounded buffer holding the most recent stdout lines so a
    * failure can surface a useful tail in its diagnostic instead
    * of an opaque "didn't start" message. */
  private val OutputTailLimit: Int = 50

  /** Monitor the subprocess until ONE of:
    *   - `.metals/mcp.json` lands → register McpServerConfig +
    *     resolve with the workspace name.
    *   - subprocess exits before mcp.json → fail with the exit
    *     code + captured stdout tail.
    *   - workflow cancellation token fires → kill the subprocess
    *     and fail with the cancellation reason.
    *
    * No wall-clock deadline, no stuck-detection threshold. The
    * framework trusts the subprocess: if it's alive, it's
    * working. The user-perceived "this is taking too long" case
    * is handled by `cancel_framework_workflow` — the workflow
    * surfaces in the activity bar with progress notices, the
    * user cancels, the cancellation token kills the subprocess.
    * Bug #68. */
  private def waitForReady(entry: Entry,
                           control: sigil.FrameworkWorkflowControl,
                           tail: java.util.concurrent.LinkedBlockingDeque[String]): Task[String] = {
    def loop: Task[String] = Task.defer {
      if (control.token.isCancelled) {
        killSubprocess(entry)
        entries.remove(entry.workspace.toAbsolutePath.normalize.toString)
        Task.error(new sigil.CancellationException(
          workflowId = control.token.workflowId,
          reason     = control.token.reason
        ))
      } else if (!entry.process.isAlive) {
        // Subprocess exited before mcp.json appeared — fail with
        // exit code + recent output for the diagnostic. Briefly
        // join the stderr drainer so any buffered output is in
        // the tail snapshot before we render it.
        val exitCode = scala.util.Try(entry.process.exitValue()).getOrElse(-1)
        try entry.stderrDrainer.join(500L) catch { case _: InterruptedException => () }
        val tailSnap = drainTail(tail)
        entries.remove(entry.workspace.toAbsolutePath.normalize.toString)
        Task.error(new RuntimeException(
          s"MetalsManager: ${entry.workspaceKey} exited (code=$exitCode) before writing .metals/mcp.json. " +
            s"Recent output:\n$tailSnap"
        ))
      } else {
        MetalsRendezvous.read(entry.workspace) match {
          case Some(ep) =>
            entry.endpoint = Some(ep.url)
            control.step(s"Metals registered endpoint: ${ep.url}").flatMap { _ =>
              host.mcpManager.addConfig(serverConfigFor(entry.workspaceKey, ep.url, entry.workspace))
                .map(_ => entry.workspaceKey)
            }
          case None =>
            Task.sleep(PollIntervalMs.millis).flatMap(_ => loop)
        }
      }
    }
    loop
  }

  private def drainTail(tail: java.util.concurrent.LinkedBlockingDeque[String]): String = {
    import scala.jdk.CollectionConverters.*
    tail.iterator().asScala.toList.takeRight(OutputTailLimit).mkString("\n")
  }

  private def killSubprocess(entry: Entry): Unit = {
    if (entry.process.isAlive) entry.process.destroy()
    if (entry.process.isAlive) entry.process.destroyForcibly()
  }


  private def serverConfigFor(name: String, endpoint: String, workspace: Path): McpServerConfig = {
    val url = URL.get(endpoint, tldValidation = TLDValidation.Off) match {
      case Right(u) => u
      case Left(err) =>
        throw new IllegalStateException(
          s"MetalsManager: rendezvous endpoint '$endpoint' isn't a valid URL: $err"
        )
    }
    McpServerConfig(
      name      = name,
      transport = McpTransport.HttpSse(url, headers = Map.empty),
      prefix    = None,
      // The roots advertised to the server during MCP `initialize`
      // include the workspace path so Metals knows which project
      // to index. (Metals usually picks this up from cwd, but
      // advertising explicitly is the spec-correct posture.)
      roots     = List(workspace.toAbsolutePath.normalize.toString),
      modified  = Timestamp()
    )
  }

  private def teardownEntry(entry: Entry): Task[Unit] = Task.defer {
    Task {
      if (entry.process.isAlive) {
        entry.process.destroy()
        if (!entry.process.waitFor(2L, java.util.concurrent.TimeUnit.SECONDS)) {
          entry.process.destroyForcibly()
        }
      }
    }.flatMap { _ =>
      host.mcpManager.removeConfig(entry.workspaceKey).handleError { t =>
        Task(scribe.warn(s"MetalsManager: failed to remove McpServerConfig ${entry.workspaceKey}: ${t.getMessage}"))
      }.unit
    }
  }

  /** Background fiber doing two things on the same poll loop:
    *  1. Re-check each entry's `.metals/mcp.json` for endpoint
    *     drift (port change after a Metals restart). On change,
    *     update the McpServerConfig and force the McpManager to
    *     reconnect via [[sigil.mcp.McpManager.closeClient]].
    *  2. Reap entries idle past [[MetalsSigil.metalsIdleTimeoutMs]].
    *
    * Started lazily on the first `ensureRunning` call. */
  private def ensureBackgroundFibers(): Unit = synchronized {
    if (started.compareAndSet(false, true)) {
      // Register a JVM shutdown hook so a process killed without
      // calling `Sigil.shutdown` still drops Metals subprocesses
      // (best-effort — a SIGKILL on the parent leaves orphans).
      Runtime.getRuntime.addShutdownHook(new Thread(() => {
        try shutdown.sync()
        catch { case _: Throwable => () }
      }, "sigil-metals-shutdown"))

      pollLoop.startUnit()
    }
  }

  private def pollLoop: Task[Unit] = Task.defer {
    if (stopped.get()) Task.unit
    else {
      import scala.jdk.CollectionConverters.*
      val now = System.currentTimeMillis()
      val idleCutoff = now - host.metalsIdleTimeoutMs
      val all = entries.entrySet().asScala.toList
      val maintenance = Task.sequence(all.map { e =>
        val entry = e.getValue
        if (!entry.process.isAlive) {
          // Subprocess died unexpectedly; clean up the row so the
          // next ensureRunning respawns rather than reusing dead state.
          entries.remove(e.getKey)
          host.mcpManager.removeConfig(entry.workspaceKey).handleError(_ => Task.unit).unit
        } else if (entry.lastUsedMs < idleCutoff) {
          // Idle reap.
          entries.remove(e.getKey)
          teardownEntry(entry).handleError(_ => Task.unit)
        } else {
          // Endpoint-drift check.
          MetalsRendezvous.read(entry.workspace) match {
            case Some(ep) if !entry.endpoint.contains(ep.url) =>
              entry.endpoint = Some(ep.url)
              val updated = serverConfigFor(entry.workspaceKey, ep.url, entry.workspace)
              host.mcpManager.addConfig(updated)
                .flatMap(_ => host.mcpManager.closeClient(entry.workspaceKey))
                .handleError { t =>
                  Task(scribe.warn(s"MetalsManager: endpoint update for ${entry.workspaceKey} failed: ${t.getMessage}"))
                }.unit
            case _ => Task.unit
          }
        }
      }).unit
      maintenance.flatMap(_ => Task.sleep(PollIntervalMs.millis)).flatMap(_ => pollLoop)
    }
  }

  /** Drain the subprocess's stderr line-by-line. With
    * `redirectErrorStream(false)` (required so the LSP wire on
    * stdout stays parseable), Metals' real log output + fatal
    * messages land here. Lines feed the failure-diagnostic tail
    * buffer AND fan out as ToolLog events for the chat chip. */
  private def startStderrDrainer(process: Process,
                                  label: String,
                                  tail: java.util.concurrent.LinkedBlockingDeque[String],
                                  onLogLine: AtomicReference[Option[String => Task[Unit]]]): Thread = {
    val t = new Thread(() => {
      val reader = new java.io.BufferedReader(
        new java.io.InputStreamReader(process.getErrorStream, java.nio.charset.StandardCharsets.UTF_8)
      )
      try {
        var line = reader.readLine()
        while (line != null) {
          scribe.info(s"[$label] $line")
          if (!tail.offer(line)) {
            tail.pollFirst()
            tail.offer(line)
          }
          onLogLine.get().foreach(cb => cb(line).handleError(_ => Task.unit).startUnit())
          line = reader.readLine()
        }
      } catch { case _: Throwable => () }
      finally {
        try reader.close() catch { case _: Throwable => () }
      }
    }, s"sigil-metals-stderr-drainer-$label")
    t.setDaemon(true)
    t.start()
    t
  }
}

object MetalsManager {

  /** Snapshot of one workspace's Metals state for status surfaces
    * (chip, list tool, dashboards). `endpoint` is `None` while the
    * subprocess is starting up before `.metals/mcp.json` lands;
    * `alive` is `false` immediately after a crash, before the
    * sweeper reaps the entry. */
  final case class WorkspaceStatus(workspace: Path,
                                   workspaceKey: String,
                                   endpoint: Option[String],
                                   alive: Boolean,
                                   lastUsedMs: Long)
}
