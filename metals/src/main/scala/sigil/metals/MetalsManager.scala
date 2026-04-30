package sigil.metals

import lightdb.time.Timestamp
import rapid.Task
import sigil.Sigil
import sigil.db.SigilDB
import sigil.mcp.{McpCollections, McpServerConfig, McpTransport}
import spice.net.{TLDValidation, URL}

import java.nio.file.{Files, Path}
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import scala.concurrent.duration.*

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
    * [[MetalsWorkspaceKey]]-derived `metals-<hash>` server name. */
  private final case class Entry(workspace: Path,
                                 workspaceKey: String,
                                 process: Process,
                                 @volatile var endpoint: Option[String],
                                 @volatile var lastUsedMs: Long)

  /** Keyed by canonical workspace path string. */
  private val entries: ConcurrentHashMap[String, Entry] = new ConcurrentHashMap()

  private val started: AtomicBoolean = new AtomicBoolean(false)
  private val stopped: AtomicBoolean = new AtomicBoolean(false)

  /** Time the manager waits for `.metals/mcp.json` to appear after
    * spawning Metals. Beyond this, [[ensureRunning]] gives up,
    * tears down the subprocess, and reports failure. */
  private val SpawnTimeoutMs: Long = 30L * 1000L

  /** Watcher / reaper poll cadence. */
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
  def ensureRunning(workspace: Path): Task[String] = Task.defer {
    val canonical = workspace.toAbsolutePath.normalize
    val key = canonical.toString
    val name = MetalsWorkspaceKey.of(canonical)
    val existing = Option(entries.get(key))
    existing match {
      case Some(entry) if entry.process.isAlive =>
        entry.lastUsedMs = System.currentTimeMillis()
        Task.pure(entry.workspaceKey)
      case _ =>
        // No live entry — spawn and wait for rendezvous, then
        // upsert the McpServerConfig.
        spawnAndRegister(canonical, key, name)
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

  private def spawnAndRegister(workspace: Path, key: String, name: String): Task[String] = Task.defer {
    if (!Files.isDirectory(workspace)) {
      Task.error(new IllegalArgumentException(
        s"MetalsManager: workspace $workspace is not a directory"
      ))
    } else {
      ensureBackgroundFibers()
      val pb = new ProcessBuilder(host.metalsLauncher*)
        .directory(workspace.toFile)
        .redirectErrorStream(true)
      try {
        val process = pb.start()
        // Drain stdout on a daemon so the subprocess buffer never
        // back-pressures Metals into hanging.
        startDrainerThread(process, name)
        waitForRendezvous(workspace, process).flatMap {
          case None =>
            // Timeout — Metals didn't write the rendezvous file.
            // Kill the subprocess and surface a clear error.
            process.destroy()
            if (process.isAlive) process.destroyForcibly()
            Task.error(new RuntimeException(
              s"MetalsManager: $name didn't write .metals/mcp.json within ${SpawnTimeoutMs}ms"
            ))
          case Some(endpoint) =>
            val entry = Entry(
              workspace    = workspace,
              workspaceKey = name,
              process      = process,
              endpoint     = Some(endpoint),
              lastUsedMs   = System.currentTimeMillis()
            )
            entries.put(key, entry)
            host.mcpManager.addConfig(serverConfigFor(name, endpoint, workspace))
              .map(_ => name)
        }
      } catch {
        case t: Throwable =>
          Task.error(new RuntimeException(
            s"MetalsManager: failed to launch Metals (${host.metalsLauncher.mkString(" ")}) for $workspace: ${t.getMessage}",
            t
          ))
      }
    }
  }

  private def waitForRendezvous(workspace: Path, process: Process): Task[Option[String]] = Task {
    val deadline = System.currentTimeMillis() + SpawnTimeoutMs
    var endpoint: Option[String] = None
    while (endpoint.isEmpty && System.currentTimeMillis() < deadline && process.isAlive) {
      MetalsRendezvous.read(workspace) match {
        case Some(ep) => endpoint = Some(ep.url)
        case None     => Thread.sleep(PollIntervalMs)
      }
    }
    endpoint
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
      prefix    = "",
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

  private def startDrainerThread(process: Process, label: String): Unit = {
    val t = new Thread(() => {
      val reader = new java.io.BufferedReader(
        new java.io.InputStreamReader(process.getInputStream, java.nio.charset.StandardCharsets.UTF_8)
      )
      try {
        var line = reader.readLine()
        while (line != null) {
          // Tag every line so multi-Metals deployments stay legible.
          scribe.debug(s"[$label] $line")
          line = reader.readLine()
        }
      } catch { case _: Throwable => () }
      finally {
        try reader.close() catch { case _: Throwable => () }
      }
    }, s"sigil-metals-drainer-$label")
    t.setDaemon(true)
    t.start()
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
