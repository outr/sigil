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
        keepaliveOnlyBudgetMs = 0L,
        reliefMs = 0L,
        relief = None,
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
        keepaliveOnlyBudgetMs = 0L,
        reliefMs = 0L,
        relief = None,
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
        keepaliveOnlyBudgetMs = 0L,
        reliefMs = 0L,
        relief = None,
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
        keepaliveOnlyBudgetMs = 0L,
        reliefMs = 0L,
        relief = None,
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
        keepaliveOnlyBudgetMs = 0L,
        reliefMs = 0L,
        relief = None,
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

  /** Counting relief stub — records stall/clear pairing. */
  private final class RecordingRelief extends sigil.provider.StreamStarvationRelief {
    val stalls = new java.util.concurrent.atomic.AtomicInteger(0)
    val clears = new java.util.concurrent.atomic.AtomicInteger(0)
    override def stall(): Unit = { stalls.incrementAndGet(); () }
    override def clear(): Unit = { clears.incrementAndGet(); () }
  }

  "the keepalive-only clock (#420)" should {

    "fire within tolerance of the budget with NO further line arrivals" in {
      // The #420 field failure: the lazy check fired at 5.2× the budget
      // because no keepalive line arrived to evaluate it. The timer
      // must own this clock — arm the stream with one keepalive, then
      // total line-silence (line budget OFF, as llama configures).
      val state = newState(keepaliveMs = 400L)
      val cancelled = new CountDownLatch(1)
      val stopped = new AtomicBoolean(false)
      OpenAIChatCompletions.parseLine(": keep-alive", state, cfg) shouldBe empty
      val startedAt = System.currentTimeMillis()
      StreamSilenceWatchdog.run(
        state = state,
        config = cfg,
        postContentBudgetMs = 0L,
        preContentBudgetMs = 0L,
        keepaliveOnlyBudgetMs = 400L,
        reliefMs = 0L,
        relief = None,
        cancel = Task { cancelled.countDown() },
        stopped = stopped
      ).startUnit()
      for {
        fired <- Task(cancelled.await(5, java.util.concurrent.TimeUnit.SECONDS))
        elapsed = System.currentTimeMillis() - startedAt
      } yield {
        stopped.set(true)
        fired shouldBe true
        elapsed should be >= 350L
        elapsed should be <= 2000L
        state.lineSilenceBreach shouldBe defined
        val ex = intercept[ProviderStreamException](state.closeStream(cfg))
        ex.typ shouldBe "upstream_silent"
        ex.getMessage should include ("keepalive-only budget")
      }
    }

    "not fire while meaningful progress keeps resetting the anchor" in {
      val state = newState(keepaliveMs = 400L)
      val cancelled = new CountDownLatch(1)
      val stopped = new AtomicBoolean(false)
      OpenAIChatCompletions.parseLine(": keep-alive", state, cfg) shouldBe empty
      StreamSilenceWatchdog.run(
        state = state,
        config = cfg,
        postContentBudgetMs = 0L,
        preContentBudgetMs = 0L,
        keepaliveOnlyBudgetMs = 400L,
        reliefMs = 0L,
        relief = None,
        cancel = Task { cancelled.countDown() },
        stopped = stopped
      ).startUnit()
      def pulse(remaining: Int): Task[Unit] =
        if (remaining <= 0) Task.unit
        else Task.sleep(150.millis).flatMap { _ =>
          state.markMeaningfulProgress()
          pulse(remaining - 1)
        }
      for {
        _ <- pulse(8) // 1.2s of steady progress against a 400ms budget
      } yield {
        stopped.set(true)
        cancelled.getCount shouldBe 1L
        state.lineSilenceBreach shouldBe empty
      }
    }
  }

  "starvation relief" should {

    "engage at the relief threshold and clear when meaningful content arrives" in {
      val state = newState(keepaliveMs = 10000L)
      val relief = new RecordingRelief
      val stopped = new AtomicBoolean(false)
      OpenAIChatCompletions.parseLine(": keep-alive", state, cfg) shouldBe empty
      StreamSilenceWatchdog.run(
        state = state,
        config = cfg,
        postContentBudgetMs = 0L,
        preContentBudgetMs = 0L,
        keepaliveOnlyBudgetMs = 10000L,
        reliefMs = 200L,
        relief = Some(relief),
        cancel = Task.unit,
        stopped = stopped
      ).startUnit()
      for {
        // Past the 200ms relief threshold with only the arming keepalive.
        _ <- Task.sleep(700.millis)
        stalledAt = relief.stalls.get()
        // The starved stream finally gets served.
        _ <- Task { state.markMeaningfulProgress() }
        _ <- Task.sleep(500.millis)
      } yield {
        stopped.set(true)
        stalledAt shouldBe 1
        relief.clears.get() shouldBe 1
        state.lineSilenceBreach shouldBe empty
      }
    }

    "clear an active stall when the stream terminates" in {
      val state = newState(keepaliveMs = 10000L)
      val relief = new RecordingRelief
      val stopped = new AtomicBoolean(false)
      OpenAIChatCompletions.parseLine(": keep-alive", state, cfg) shouldBe empty
      StreamSilenceWatchdog.run(
        state = state,
        config = cfg,
        postContentBudgetMs = 0L,
        preContentBudgetMs = 0L,
        keepaliveOnlyBudgetMs = 10000L,
        reliefMs = 200L,
        relief = Some(relief),
        cancel = Task.unit,
        stopped = stopped
      ).startUnit()
      for {
        _ <- Task.sleep(700.millis)
        _ <- Task { stopped.set(true) }
        _ <- Task.sleep(400.millis)
      } yield {
        relief.stalls.get() shouldBe 1
        // Exactly one clear — the terminal path pairs the stall.
        relief.clears.get() shouldBe 1
      }
    }

    "clear an active stall when the keepalive budget breaches" in {
      val state = newState(keepaliveMs = 600L)
      val relief = new RecordingRelief
      val cancelled = new CountDownLatch(1)
      val stopped = new AtomicBoolean(false)
      OpenAIChatCompletions.parseLine(": keep-alive", state, cfg) shouldBe empty
      StreamSilenceWatchdog.run(
        state = state,
        config = cfg,
        postContentBudgetMs = 0L,
        preContentBudgetMs = 0L,
        keepaliveOnlyBudgetMs = 600L,
        reliefMs = 200L,
        relief = Some(relief),
        cancel = Task { cancelled.countDown() },
        stopped = stopped
      ).startUnit()
      for {
        fired <- Task(cancelled.await(5, java.util.concurrent.TimeUnit.SECONDS))
      } yield {
        stopped.set(true)
        fired shouldBe true
        relief.stalls.get() shouldBe 1
        relief.clears.get() shouldBe 1
        state.lineSilenceBreach shouldBe defined
      }
    }
  }
}
