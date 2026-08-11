package sigil.browser.stream

import lightdb.id.Id
import rapid.Task
import robobrowser.RoboBrowser
import sigil.conversation.Conversation

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import scala.jdk.CollectionConverters.*

/**
 * Per-conversation singleton wrapping the headful, virtual-display
 * [[RoboBrowser]] that preview streams capture. The sibling of
 * [[sigil.browser.BrowserController]] — same lazy-allocate / idle-reap /
 * dispose-on-delete lifecycle — but it additionally owns the live
 * [[PreviewStreamSession]]s, since routing a viewer's signaling reply
 * means finding the session by `streamId`.
 *
 * A controller with at least one live session is never idle: someone is
 * watching. Only a preview-less browser ages out.
 */
final class StreamBrowserController private[stream] (val conversationId: Id[Conversation],
                                                     val browser: RoboBrowser) {

  private val _sessions: ConcurrentHashMap[String, PreviewStreamSession] =
    new ConcurrentHashMap[String, PreviewStreamSession]()
  @volatile private var _lastTouchMs: Long = System.currentTimeMillis()
  private val _disposed: AtomicBoolean = new AtomicBoolean(false)

  /** Run a block against the live browser, marking the controller
    * touched so the idle reaper doesn't claim it mid-action. */
  def run[A](f: RoboBrowser => Task[A]): Task[A] =
    if (_disposed.get())
      Task.error(new IllegalStateException(s"StreamBrowserController for $conversationId is disposed"))
    else {
      touch()
      f(browser)
    }

  def sessions: List[PreviewStreamSession] = _sessions.values().asScala.toList

  def session(streamId: String): Option[PreviewStreamSession] = Option(_sessions.get(streamId))

  private[stream] def register(session: PreviewStreamSession): Unit = {
    _sessions.put(session.streamId, session)
    touch()
  }

  private[stream] def deregister(streamId: String): Unit = {
    _sessions.remove(streamId)
    touch()
  }

  private[stream] def touch(): Unit = _lastTouchMs = System.currentTimeMillis()

  def lastTouchMs: Long = _lastTouchMs

  def isIdle(thresholdMs: Long, now: Long = System.currentTimeMillis()): Boolean =
    _sessions.isEmpty && (now - _lastTouchMs) > thresholdMs

  def isDisposed: Boolean = _disposed.get()

  /** Stop every live session, then dispose the browser (which tears down
    * its Xvfb display). Idempotent; a failing session teardown is logged
    * and never blocks browser disposal. */
  def dispose: Task[Unit] =
    if (_disposed.compareAndSet(false, true)) {
      val live = sessions
      _sessions.clear()
      Task.sequence(live.map { s =>
        s.stop.handleError { t =>
          Task {
            scribe.warn(s"StreamBrowserController: stopping session ${s.streamId} failed: ${t.getMessage}")
            ()
          }
        }
      }).flatMap(_ => browser.dispose())
    } else Task.unit
}
