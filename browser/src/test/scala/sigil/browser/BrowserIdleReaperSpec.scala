package sigil.browser

import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.AsyncTaskSpec
import sigil.conversation.Conversation
import sigil.event.Event

import java.util.concurrent.atomic.AtomicBoolean
import scala.concurrent.duration.*

/**
 * Coverage for [[BrowserIdleReaper]] and the idle-detection logic on
 * [[BrowserController]] it relies on. Runs without a live Chrome —
 * the reaper's selection predicate (`!isDisposed && isIdle`) is the
 * wired logic under test; controllers built here carry a `null`
 * browser and the tests only exercise paths that never dispose one.
 *
 * Lives in `package sigil.browser` so it can reach
 * [[BrowserController]]'s package-private constructor and the
 * [[BrowserSigil.controllers]] registry the reaper sweeps.
 */
class BrowserIdleReaperSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {

  /** Build a controller with a `null` browser whose last-touch is set
    * `idleMs` in the past, so [[BrowserController.isIdle]] classifies
    * it deterministically without sleeping the test. */
  private def controllerIdleFor(idleMs: Long): BrowserController = {
    val convId: Id[Conversation] = Conversation.id(s"reaper-spec-${rapid.Unique()}")
    val c = new BrowserController(
      conversationId = convId,
      browser = null,
      stateId = Id[Event](rapid.Unique()),
      cookieJarId = None
    )
    val touchField = classOf[BrowserController].getDeclaredField("_lastTouchMs")
    touchField.setAccessible(true)
    touchField.setLong(c, System.currentTimeMillis() - idleMs)
    c
  }

  private def markDisposed(c: BrowserController): Unit = {
    val f = classOf[BrowserController].getDeclaredField("_disposed")
    f.setAccessible(true)
    f.get(c).asInstanceOf[AtomicBoolean].set(true)
  }

  "BrowserController.isIdle" should {
    "report a freshly-touched controller as not idle" in rapid.Task {
      controllerIdleFor(idleMs = 0L).isIdle(thresholdMs = 60_000L) shouldBe false
    }

    "report a controller untouched past the threshold as idle" in rapid.Task {
      controllerIdleFor(idleMs = 120_000L).isIdle(thresholdMs = 60_000L) shouldBe true
    }

    "treat the threshold boundary as not-yet-idle" in rapid.Task {
      val now = System.currentTimeMillis()
      // (now - lastTouch) == threshold is not strictly greater.
      controllerIdleFor(idleMs = 60_000L).isIdle(thresholdMs = 60_000L, now = now) shouldBe false
    }
  }

  "BrowserIdleReaper" should {
    "carry the configured idle timeout and a 30-second sweep interval" in rapid.Task {
      val reaper = BrowserIdleReaper(idleTimeoutMs = 5.minutes.toMillis)
      reaper.idleTimeoutMs shouldBe 5.minutes.toMillis
      reaper.interval shouldBe 30.seconds
      reaper.name shouldBe "browser-idle-reaper"
    }

    "select an idle, non-disposed controller for reaping" in rapid.Task {
      val timeout = 60_000L
      val idle = controllerIdleFor(idleMs = 120_000L)
      // The reaper's filter is `!isDisposed && isIdle(timeout)`.
      (!idle.isDisposed && idle.isIdle(timeout)) shouldBe true
    }

    "exclude a non-idle controller from reaping" in rapid.Task {
      val timeout = 60_000L
      val fresh = controllerIdleFor(idleMs = 0L)
      (!fresh.isDisposed && fresh.isIdle(timeout)) shouldBe false
    }

    "exclude an already-disposed controller even when it is idle" in rapid.Task {
      val timeout = 60_000L
      val idle = controllerIdleFor(idleMs = 120_000L)
      markDisposed(idle)
      idle.isDisposed shouldBe true
      idle.isIdle(timeout) shouldBe true
      (!idle.isDisposed && idle.isIdle(timeout)) shouldBe false
    }

    "leave a non-idle controller registered after a sweep (filter excludes it; no dispose, no browser touch)" in {
      // runOnce against a BrowserSigil host: a non-idle controller is
      // filtered out before any dispose, so the `null` browser is
      // never touched and the registry still holds the controller.
      val reaper = BrowserIdleReaper(idleTimeoutMs = 60_000L)
      val fresh = controllerIdleFor(idleMs = 0L)
      BrowserSigil.controllers.put(fresh.conversationId.value, fresh)
      reaper.runOnce(spec.TestBrowserSigil).map { _ =>
        Option(BrowserSigil.controllers.get(fresh.conversationId.value)) shouldBe Some(fresh)
        BrowserSigil.controllers.remove(fresh.conversationId.value)
        succeed
      }
    }
  }
}
