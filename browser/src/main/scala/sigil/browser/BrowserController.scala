package sigil.browser

import lightdb.id.Id
import rapid.Task
import robobrowser.RoboBrowser
import sigil.conversation.Conversation
import sigil.event.Event

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Per-conversation singleton wrapping a live [[RoboBrowser]]. Lazy-
 * allocated by [[BrowserSigil.browserController]] on first use, kept
 * open across tool calls in the same conversation, disposed when:
 *
 *   - the conversation is deleted ([[BrowserSigil]] hooks
 *     `Sigil.deleteConversation`),
 *   - the controller has been idle longer than
 *     `BrowserSigil.browserIdleTimeoutMs` (an internal reaper fiber
 *     ticks periodically),
 *   - or the JVM shuts down ([[BrowserSigil.shutdown]] cascades).
 *
 * Tools call [[run]] to perform an action against the live browser.
 * The controller updates its last-touched timestamp on every call so
 * the idle reaper doesn't claim it mid-action. CDP isn't safe under
 * concurrent calls against the same target — Sigil's per-conversation
 * agent-loop serialization handles that ordering naturally; tools
 * never run in parallel for the same conversation.
 *
 * The associated [[BrowserState]] event id is generated when the
 * controller opens. The first action that publishes a state delta
 * will have already had the underlying state record persisted via
 * the ordinary `Sigil.publish(BrowserState(...))` flow.
 */
final class BrowserController private[browser] (val conversationId: Id[Conversation],
                                                val browser: RoboBrowser,
                                                val stateId: Id[Event],
                                                val cookieJarId: Option[Id[CookieJar]] = None) {

  @volatile private var _lastTouchMs: Long = System.currentTimeMillis()
  private val _disposed: AtomicBoolean = new AtomicBoolean(false)

  /** Run a block against the live browser. Updates the
    * last-touched timestamp on entry so idle reaping doesn't fire
    * mid-action. */
  def run[A](f: RoboBrowser => Task[A]): Task[A] =
    if (_disposed.get())
      Task.error(new IllegalStateException(s"BrowserController for $conversationId is disposed"))
    else {
      _lastTouchMs = System.currentTimeMillis()
      f(browser)
    }

  def lastTouchMs: Long = _lastTouchMs

  def isIdle(thresholdMs: Long, now: Long = System.currentTimeMillis()): Boolean =
    (now - _lastTouchMs) > thresholdMs

  def isDisposed: Boolean = _disposed.get()

  /** Dispose the underlying [[RoboBrowser]]. Idempotent. */
  def dispose: Task[Unit] =
    if (_disposed.compareAndSet(false, true)) browser.dispose()
    else Task.unit
}
