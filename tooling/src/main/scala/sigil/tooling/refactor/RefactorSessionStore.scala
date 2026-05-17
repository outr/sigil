package sigil.tooling.refactor

import java.util.concurrent.ConcurrentHashMap
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/**
 * In-memory, per-Sigil-instance store of prepared refactor
 * sessions. Lost on process restart — the agent re-prepares; the
 * trade-off is accepted in exchange for not standing up a
 * persistence layer for a transient artifact.
 *
 * Thread-safe via [[ConcurrentHashMap]]. Lifecycle:
 *
 *   - [[create]] — registers a fresh session, returns its id.
 *   - [[take]]   — atomic remove. Used by the apply path so two
 *                  concurrent applies can't both win.
 *   - [[remove]] — explicit drop. Used by the cancel path; returns
 *                  whether the id was present.
 *   - [[evictExpired]] — scans for sessions older than [[ttl]]
 *                  against the supplied `now`. Returns the count
 *                  evicted; the store's own clock isn't used here
 *                  so callers (background sweep, tests) drive
 *                  expiration deterministically.
 *
 * @param ttl session lifetime. Defaults to 30 minutes — long enough
 *            for an agent to prepare, inspect, and apply across a
 *            few iterations, short enough that an abandoned
 *            session doesn't leak memory indefinitely.
 */
final class RefactorSessionStore(val ttl: FiniteDuration = 30.minutes) {

  private val sessions = new ConcurrentHashMap[String, RefactorSession]()

  /** Register a prepared session keyed by its sessionId. Overwrites
    * any prior session with the same id (callers should mint a
    * fresh id per prepare). */
  def create(session: RefactorSession): Unit = {
    sessions.put(session.sessionId, session)
    ()
  }

  /** Read without removing. Used by inspection paths that don't
    * intend to consume the session. */
  def peek(sessionId: String): Option[RefactorSession] =
    Option(sessions.get(sessionId))

  /** Atomic remove + return. The apply path uses this so two
    * concurrent applies against the same sessionId can't both
    * commit the same edits. */
  def take(sessionId: String): Option[RefactorSession] =
    Option(sessions.remove(sessionId))

  /** Drop a session by id; returns whether it was present. Used by
    * the cancel path — idempotent against repeated cancel or
    * cancel-after-apply. */
  def remove(sessionId: String): Boolean =
    sessions.remove(sessionId) != null

  /** Evict every session whose age (against `now`) exceeds [[ttl]].
    * Returns the number of sessions removed. The store doesn't
    * sweep autonomously; the host calls this on whatever cadence
    * makes sense (or, for tests, drives expiration directly). */
  def evictExpired(now: Long): Int = {
    val cutoff = now - ttl.toMillis
    val toRemove = sessions.values.iterator.asScala
      .collect { case s if s.createdAtMillis < cutoff => s.sessionId }
      .toList
    toRemove.foreach(id => sessions.remove(id))
    toRemove.size
  }

  /** Current session count. Diagnostic / metric surface. */
  def size: Int = sessions.size
}
