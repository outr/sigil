package sigil.debug

import rapid.Task
import sigil.Sigil
import sigil.db.SigilDB

import java.util.concurrent.ConcurrentHashMap
import scala.jdk.CollectionConverters.*

/**
 * Per-Sigil cache of active DAP sessions, keyed by `sessionId` —
 * an opaque string the agent picks (typically a conversation id, or
 * `"$conversationId-debug"`). One session per id; the agent calls
 * `dap_disconnect` to free.
 *
 * Unlike LSP/BSP managers, DAP sessions are short-lived (per debug
 * run) and may be many in-flight (parallel debugging of test
 * targets). The cache holds them by id so the agent can interleave
 * `dap_*` calls across calls without serializing the session handle
 * across the tool boundary.
 */
final class DapManager(sigil: Sigil { type DB <: SigilDB & DebugCollections }) {

  private val sessions: ConcurrentHashMap[String, DapSession] = new ConcurrentHashMap()

  def configFor(languageId: String): Task[Option[DebugAdapterConfig]] =
    sigil.withDB(_.debugAdapters.transaction(_.get(DebugAdapterConfig.idFor(languageId))))

  /** Get an existing session by id. Returns `None` if no session
    * with that id is currently active. */
  def get(sessionId: String): Option[DapSession] = Option(sessions.get(sessionId))

  /** Spawn a new session for the given language under the given
    * session id. Fails if a session with that id already exists —
    * disconnect first. */
  def spawn(languageId: String, sessionId: String): Task[DapSession] = Task.defer {
    Option(sessions.get(sessionId)) match {
      case Some(_) =>
        Task.error(new IllegalStateException(s"Debug session '$sessionId' already exists. Disconnect first."))
      case None =>
        configFor(languageId).flatMap {
          case None =>
            Task.error(new IllegalStateException(
              s"No DebugAdapterConfig persisted for language '$languageId'. " +
                s"Save one via Sigil.withDB(_.debugAdapters.transaction(_.upsert(DebugAdapterConfig(...)))) first."
            ))
          case Some(config) =>
            DapSession.spawn(config, sessionId).map { session =>
              sessions.put(sessionId, session)
              session
            }
        }
    }
  }

  /** Tear down a session by id. Idempotent. */
  def disconnect(sessionId: String): Task[Unit] =
    Option(sessions.remove(sessionId)) match {
      case Some(s) => s.shutdown()
      case None    => Task.unit
    }

  def shutdownAll(): Task[Unit] = Task.defer {
    val all = sessions.values.asScala.toList
    sessions.clear()
    Task.sequence(all.map(_.shutdown())).unit
  }

  /** Sweep idle sessions whose inactivity exceeds the config's
    * `idleTimeoutMs`. Called from a periodic fiber by [[DebugSigil]]. */
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

  /** Snapshot of every active session's id and current state. Useful
    * for `dap_list_sessions`. */
  def listSessions(): List[(String, DapSession)] =
    sessions.entrySet().asScala.toList.map(e => e.getKey -> e.getValue)
}
