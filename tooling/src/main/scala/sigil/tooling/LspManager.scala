package sigil.tooling

import org.eclipse.lsp4j.{FileChangeType, FileEvent}
import rapid.Task
import sigil.Sigil
import sigil.db.SigilDB

import java.io.File
import java.nio.file.{Files, Path, Paths}
import java.util.concurrent.ConcurrentHashMap
import scala.jdk.CollectionConverters.*

/**
 * Per-Sigil cache of language-server sessions, keyed by
 * `(languageId, projectRoot)`. Lazy: a session spins up the first
 * time a tool requests it for a given key and stays warm until its
 * config's `idleTimeoutMs` elapses with no calls.
 *
 * One [[LspServerConfig]] (DB-persisted) maps to N sessions — one
 * per project root the agent touches. Apps that want a single shared
 * Metals across all Scala projects override [[resolveRoot]] to
 * collapse all Scala files to one canonical root.
 *
 * The [[applier]] is shared across every session this manager
 * spawns; it determines what happens when a server requests
 * `workspace/applyEdit` (rename, code-action, etc.). The default
 * is permissive — apps that want to sandbox the agent's edit
 * footprint pass [[WorkspaceEditApplier.Sandboxed]] or a custom impl.
 */
final class LspManager(sigil: Sigil { type DB <: SigilDB & ToolingCollections },
                       applier: WorkspaceEditApplier = PermissiveWorkspaceEditApplier) {

  private val sessions: ConcurrentHashMap[(String, String), LspSession] = new ConcurrentHashMap()

  def configFor(languageId: String): Task[Option[LspServerConfig]] =
    sigil.withDB(_.lspServers.transaction(_.get(LspServerConfig.idFor(languageId))))

  def resolveRoot(filePath: String, rootMarkers: List[String]): String = {
    val start = Paths.get(filePath).toAbsolutePath
    val initial = if (Files.isDirectory(start)) start else start.getParent
    if (rootMarkers.isEmpty) initial.toString
    else {
      var current: Path = initial
      while (current != null) {
        if (rootMarkers.exists(m => Files.exists(current.resolve(m)))) return current.toString
        current = current.getParent
      }
      initial.toString
    }
  }

  def session(languageId: String, projectRoot: String): Task[LspSession] = Task.defer {
    val key = (languageId, projectRoot)
    Option(sessions.get(key)) match {
      case Some(existing) =>
        existing.touch()
        Task.pure(existing)
      case None =>
        configFor(languageId).flatMap {
          case None =>
            Task.error(new IllegalStateException(
              s"No LspServerConfig persisted for language '$languageId'. " +
                s"Save one via Sigil.withDB(_.lspServers.transaction(_.upsert(LspServerConfig(...)))) first."
            ))
          case Some(config) =>
            LspSession.spawn(config, projectRoot, applier).map { session =>
              val prior = sessions.putIfAbsent(key, session)
              if (prior != null) {
                session.shutdown().sync()
                prior.touch()
                prior
              } else session
            }
        }
    }
  }

  def withSession[T](languageId: String, projectRoot: String)(f: LspSession => Task[T]): Task[T] =
    session(languageId, projectRoot).flatMap(f)

  /** Fan-out a file-change notification to every session whose
    * project root is an ancestor of the given path. Apps wire this
    * from their `EditFileTool` / `WriteFileTool` so language servers
    * pick up framework-side writes the same way they would pick up
    * an editor save. */
  def notifyFileChanged(absolutePath: String, kind: FileChangeType = FileChangeType.Changed): Task[Unit] = Task.defer {
    val abs = Paths.get(absolutePath).toAbsolutePath.normalize()
    val uri = abs.toUri.toString
    val event = new FileEvent(uri, kind)
    val targets = sessions.entrySet().asScala.toList.collect {
      case e if abs.startsWith(Paths.get(e.getKey._2).toAbsolutePath.normalize()) => e.getValue
    }
    Task.sequence(targets.map(_.didChangeWatchedFiles(List(event)).handleError(_ => Task.unit))).unit
  }

  /** Same as [[notifyFileChanged]] but for a batch — preferred when
    * a single agent action touches many files (e.g. an apply-edit
    * fan-out). */
  def notifyFilesChanged(events: Map[String, FileChangeType]): Task[Unit] = Task.defer {
    val byRoot = sessions.entrySet().asScala.toList.flatMap { e =>
      val rootPath = Paths.get(e.getKey._2).toAbsolutePath.normalize()
      val matched = events.toList.collect {
        case (path, kind) if Paths.get(path).toAbsolutePath.normalize().startsWith(rootPath) =>
          new FileEvent(Paths.get(path).toAbsolutePath.normalize().toUri.toString, kind)
      }
      if (matched.isEmpty) Nil else List(e.getValue -> matched)
    }
    Task.sequence(byRoot.map { case (sess, ev) =>
      sess.didChangeWatchedFiles(ev).handleError(_ => Task.unit)
    }).unit
  }

  def shutdown(languageId: String, projectRoot: String): Task[Unit] =
    Option(sessions.remove((languageId, projectRoot))) match {
      case Some(s) => s.shutdown()
      case None    => Task.unit
    }

  def shutdownAll(): Task[Unit] = Task.defer {
    val all = sessions.values.asScala.toList
    sessions.clear()
    Task.sequence(all.map(_.shutdown())).unit
  }

  def sweepIdle(): Task[Unit] = Task.defer {
    val now = System.currentTimeMillis()
    val expired = sessions.entrySet().asScala.filter { e =>
      now - e.getValue.idleSince >= e.getValue.config.idleTimeoutMs
    }.toList
    Task.sequence(expired.map { e =>
      sessions.remove(e.getKey, e.getValue)
      e.getValue.shutdown()
    }).unit
  }
}
