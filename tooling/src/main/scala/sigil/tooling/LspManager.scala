package sigil.tooling

import lightdb.id.Id
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
 * Shutdown is fire-and-forget on a daemon scheduler; an agent that
 * touches a server, walks away, and comes back within the timeout
 * pays no warm-up cost on the second visit.
 */
final class LspManager(sigil: Sigil { type DB <: SigilDB & ToolingCollections }) {

  private val sessions: ConcurrentHashMap[(String, String), LspSession] = new ConcurrentHashMap()

  /** Resolve a config record for the given language id, or None if
    * the app hasn't persisted one. Tools surface a clear error to
    * the agent when this is None — auto-discovery is intentionally
    * not in scope here. Apps wire MetalsAutoConfig (etc.) themselves. */
  def configFor(languageId: String): Task[Option[LspServerConfig]] =
    sigil.withDB(_.lspServers.transaction(_.get(LspServerConfig.idFor(languageId))))

  /** Walk up from `filePath` looking for any of `rootMarkers`. The
    * first directory containing one of them is the project root.
    * Falls back to the file's parent directory if no marker hits. */
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

  /** Get-or-create a session. The first call spawns the subprocess
    * and waits for `initialize` to complete; subsequent calls touch
    * the existing session and return immediately. */
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
            LspSession.spawn(config, projectRoot).map { session =>
              val prior = sessions.putIfAbsent(key, session)
              if (prior != null) {
                // Race — another caller spawned first; tear ours down.
                session.shutdown().sync()
                prior.touch()
                prior
              } else session
            }
        }
    }
  }

  /** Convenience — `session` + the call, with `lastUseAt` bumped
    * implicitly by every method on `LspSession`. */
  def withSession[T](languageId: String, projectRoot: String)(f: LspSession => Task[T]): Task[T] =
    session(languageId, projectRoot).flatMap(f)

  /** Tear down a single session. Idempotent. */
  def shutdown(languageId: String, projectRoot: String): Task[Unit] =
    Option(sessions.remove((languageId, projectRoot))) match {
      case Some(s) => s.shutdown()
      case None    => Task.unit
    }

  /** Tear down every cached session. Called by [[ToolingSigil]] from
    * `Sigil.shutdown`. */
  def shutdownAll(): Task[Unit] = Task.defer {
    val all = sessions.values.asScala.toList
    sessions.clear()
    Task.sequence(all.map(_.shutdown())).unit
  }

  /** Sweep idle sessions — called from a periodic fiber by
    * [[ToolingSigil]]. Sessions whose `idleSince` exceeds their
    * config's `idleTimeoutMs` are shut down. */
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
