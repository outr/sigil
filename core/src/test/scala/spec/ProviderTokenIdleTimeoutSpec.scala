package spec

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Stream, Task}

import scala.concurrent.duration.*

/**
 * Coverage for the wall-clock-vs-idle stream timeout behavior.
 *
 * The framework's previous shape combined okhttp's per-read timeout
 * (which fires only when no bytes arrive for the duration — idle
 * semantics) with rapid `Stream.timeout` (a wall-clock deadline that
 * fires after total elapsed time exceeds the duration regardless of
 * activity). The wall-clock kill was added to catch keepalive-only
 * streams but turned into a false-positive killer of slow-but-working
 * streams: a model emitting tokens steadily but slowly hit the wall
 * clock and got terminated mid-generation.
 *
 * The new shape relies on okhttp's per-read timeout alone — slow
 * streams emitting steadily survive; genuinely stalled streams
 * (no bytes arriving) fail naturally at the HTTP layer.
 *
 * This spec verifies the underlying primitive behavior:
 *   1. rapid `Stream.timeout` IS wall-clock — a steadily-streaming
 *      slow source dies at the deadline regardless of activity.
 *   2. Without it, the same slow source completes naturally.
 *
 * Regression: if rapid's `Stream.timeout` ever changes to be
 * idle-based, case 1 starts passing too — an obvious signal to
 * re-evaluate the framework's stream-lifetime policy.
 */
class ProviderTokenIdleTimeoutSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {

  /**
   * Synthetic source: emits N elements with `gap` between each.
   */
  private def slowStream(elements: Int, gap: FiniteDuration): Stream[Int] =
    Stream.emits((0 until elements).toList).evalMap { i =>
      Task { Thread.sleep(gap.toMillis); i }
    }

  "Stream.timeout (rapid wall-clock semantic)" should {

    "kill a slow-but-steadily-streaming source after the wall-clock deadline" in {
      // 10 elements × 50ms gap = 500ms total; deadline 100ms forces
      // the wall-clock kill mid-stream.
      val source = slowStream(elements = 10, gap = 50.millis)
      val gated = source.timeout(100.millis)
      gated.toList.attempt.map { result =>
        result.isFailure shouldBe true
        result.failed.get.getClass.getName should include("TimeoutException")
      }
    }

    "let the same slow source complete when no wall-clock deadline is applied" in {
      // Without `.timeout(...)`, the slow source completes all 10
      // elements regardless of total duration. This is the framework's
      // post-fix behavior: per-read idle is enforced at the HTTP
      // layer, not via rapid wall-clock.
      val source = slowStream(elements = 10, gap = 50.millis)
      source.toList.map { collected =>
        collected.size shouldBe 10
      }
    }
  }
}
