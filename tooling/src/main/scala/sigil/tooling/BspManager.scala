package sigil.tooling

import rapid.Task
import sigil.Sigil
import sigil.db.SigilDB

import java.util.concurrent.ConcurrentHashMap
import scala.jdk.CollectionConverters.*

/**
 * Per-Sigil cache of BSP sessions, keyed by project root. Mirrors
 * [[LspManager]] in shape — lazy connect, idle sweep, manual shutdown.
 *
 * BSP servers are heavy (sbt warm-up can take minutes), so the
 * default idle window in [[BspBuildConfig]] is generous. Apps that
 * jump across many builds override `idleTimeoutMs` per config.
 */
final class BspManager(sigil: Sigil { type DB <: SigilDB & ToolingCollections }) {

  private val sessions: ConcurrentHashMap[String, BspSession] = new ConcurrentHashMap()

  def configFor(projectRoot: String): Task[Option[BspBuildConfig]] =
    sigil.withDB(_.bspBuilds.transaction(_.get(BspBuildConfig.idFor(projectRoot))))

  def session(projectRoot: String): Task[BspSession] = Task.defer {
    Option(sessions.get(projectRoot)) match {
      case Some(existing) =>
        existing.touch()
        Task.pure(existing)
      case None =>
        configFor(projectRoot).flatMap {
          case None =>
            Task.error(new IllegalStateException(
              s"No BspBuildConfig persisted for project root '$projectRoot'. " +
                s"Save one via Sigil.withDB(_.bspBuilds.transaction(_.upsert(BspBuildConfig(...)))) first."
            ))
          case Some(config) =>
            BspSession.spawn(config).map { session =>
              val prior = sessions.putIfAbsent(projectRoot, session)
              if (prior != null) {
                session.shutdown().sync()
                prior.touch()
                prior
              } else session
            }
        }
    }
  }

  def withSession[T](projectRoot: String)(f: BspSession => Task[T]): Task[T] =
    session(projectRoot).flatMap(f)

  def shutdown(projectRoot: String): Task[Unit] =
    Option(sessions.remove(projectRoot)) match {
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
