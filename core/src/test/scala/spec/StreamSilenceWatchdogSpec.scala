package spec

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Task}
import sigil.provider.{ProviderStreamException, ToolCallAccumulator}
import sigil.provider.wire.{OpenAIChatCompletions, StreamSilenceWatchdog}
import sigil.provider.wire.OpenAIChatCompletions.{Config, StreamState}

import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import scala.concurrent.duration.*

/**
 * The timer-enforced true-line-silence watchdog. The lazy per-line
 * check could only evaluate when a line arrived, so it never fired for
 * a genuinely silent upstream and fired ~9× late (on the NEXT line —
 * often the first productive chunk) for a queued one. The watchdog must:
 *
 *   1. fire within a small tolerance OF the configured threshold when
 *      no lines arrive at all — not at 9× it, and not never;
 *   2. treat every arriving line (keepalives included) as liveness
 *      that resets the clock;
 *   3. select the dead-on-arrival budget before first content and the
 *      full budget after.
 */
class StreamSilenceWatchdogSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {

  private def newState(silenceMs: Long = 0L, doaMs: Long = 0L, keepaliveMs: Long = 0L): StreamState =
    new StreamState(
      acc = new ToolCallAccumulator(Vector.empty, providerKey = "test"),
      streamingSilenceTimeoutMs = silenceMs,
      streamingDeadOnArrivalTimeoutMs = doaMs,
      streamingKeepaliveOnlyTimeoutMs = keepaliveMs
    )

  private val cfg = Config(providerNamespace = "test", providerName = "TestBackend")

  "the silence watchdog" should {

    "fire within tolerance of the threshold when no lines arrive at all" in {
      val state = newState(silenceMs = 400L)
      val cancelled = new CountDownLatch(1)
      val stopped = new AtomicBoolean(false)
      val startedAt = System.currentTimeMillis()
      StreamSilenceWatchdog.run(
        state = state,
        config = cfg,
        postContentBudgetMs = 400L,
        preContentBudgetMs = 0L,
        cancel = Task { cancelled.countDown() },
        stopped = stopped
      ).startUnit()
      for {
        fired <- Task(cancelled.await(5, java.util.concurrent.TimeUnit.SECONDS))
        elapsed = System.currentTimeMillis() - startedAt
      } yield {
        stopped.set(true)
        fired shouldBe true
        // The lazy predecessor fired at ~9× the threshold (or never).
        // One poll tick past the budget plus scheduler slop is the
        // acceptable envelope.
        elapsed should be >= 400L
        elapsed should be <= 2000L
        state.lineSilenceBreach shouldBe defined
        // The breach surfaces as the typed silence exception on the
        // stream's own close path.
        val ex = intercept[ProviderStreamException](state.closeStream(cfg))
        ex.typ shouldBe "upstream_silent"
        ex.getMessage should include ("no stream lines")
      }
    }

    "treat arriving keepalive lines as liveness that resets the clock" in {
      val state = newState(silenceMs = 400L)
      val cancelled = new CountDownLatch(1)
      val stopped = new AtomicBoolean(false)
      StreamSilenceWatchdog.run(
        state = state,
        config = cfg,
        postContentBudgetMs = 400L,
        preContentBudgetMs = 0L,
        cancel = Task { cancelled.countDown() },
        stopped = stopped
      ).startUnit()
      // Keepalives every 100ms for 3× the budget — the connection is
      // alive (queued behind load); the watchdog must not fire.
      def pulse(remaining: Int): Task[Unit] =
        if (remaining <= 0) Task.unit
        else Task.sleep(100.millis).flatMap { _ =>
          OpenAIChatCompletions.parseLine(": keepalive", state, cfg)
          pulse(remaining - 1)
        }
      for {
        _ <- pulse(12)
      } yield {
        stopped.set(true)
        cancelled.getCount shouldBe 1L
        state.lineSilenceBreach shouldBe empty
        state.closeStream(cfg) shouldBe empty
      }
    }

    "apply the dead-on-arrival budget before first content" in {
      val state = newState(silenceMs = 10000L, doaMs = 300L)
      val cancelled = new CountDownLatch(1)
      val stopped = new AtomicBoolean(false)
      val startedAt = System.currentTimeMillis()
      StreamSilenceWatchdog.run(
        state = state,
        config = cfg,
        postContentBudgetMs = 10000L,
        preContentBudgetMs = 300L,
        cancel = Task { cancelled.countDown() },
        stopped = stopped
      ).startUnit()
      for {
        fired <- Task(cancelled.await(5, java.util.concurrent.TimeUnit.SECONDS))
        elapsed = System.currentTimeMillis() - startedAt
      } yield {
        stopped.set(true)
        fired shouldBe true
        elapsed should be >= 300L
        elapsed should be <= 1800L
        state.lineSilenceBreach shouldBe defined
      }
    }

    "apply the full budget once meaningful content has flowed" in {
      val state = newState(silenceMs = 10000L, doaMs = 300L)
      state.markMeaningfulProgress()
      val cancelled = new CountDownLatch(1)
      val stopped = new AtomicBoolean(false)
      StreamSilenceWatchdog.run(
        state = state,
        config = cfg,
        postContentBudgetMs = 10000L,
        preContentBudgetMs = 300L,
        cancel = Task { cancelled.countDown() },
        stopped = stopped
      ).startUnit()
      for {
        // Well past the 300ms dead-on-arrival budget — but content has
        // flowed, so the 10s full budget governs and nothing fires.
        _ <- Task.sleep(1200.millis)
      } yield {
        stopped.set(true)
        cancelled.getCount shouldBe 1L
        state.lineSilenceBreach shouldBe empty
      }
    }

    "stand down when the stream terminates before the budget" in {
      val state = newState(silenceMs = 500L)
      val cancelled = new CountDownLatch(1)
      val stopped = new AtomicBoolean(false)
      StreamSilenceWatchdog.run(
        state = state,
        config = cfg,
        postContentBudgetMs = 500L,
        preContentBudgetMs = 0L,
        cancel = Task { cancelled.countDown() },
        stopped = stopped
      ).startUnit()
      // The stream ends (cleanly) 100ms in — the guarantee sets the
      // stopped flag; the watchdog's next tick exits without firing.
      for {
        _ <- Task.sleep(100.millis)
        _ <- Task { stopped.set(true) }
        _ <- Task.sleep(900.millis)
      } yield {
        cancelled.getCount shouldBe 1L
        state.lineSilenceBreach shouldBe empty
      }
    }
  }
}
