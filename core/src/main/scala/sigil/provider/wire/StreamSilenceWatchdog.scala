package sigil.provider.wire

import rapid.Task

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Timer-enforced true-line-silence guard for streaming provider calls.
 *
 * The lazy per-line check can only evaluate when a line arrives — so a
 * genuinely silent upstream never trips it, and a long-queued stream is
 * killed by the FIRST line that finally arrives (the exact moment it
 * becomes productive). This watchdog runs on its own fiber and enforces
 * the budget on wall-clock time instead: when NO line of any kind has
 * arrived within the budget, it records the breach on the
 * [[OpenAIChatCompletions.StreamState]] and cancels the underlying HTTP
 * call. The cancel ends the line stream, and `closeStream` surfaces the
 * recorded breach as the typed `upstream_silent` exception on the
 * stream's own termination path.
 *
 * Keepalive / comment lines reset the clock — every arriving line is
 * affirmative liveness. The keepalive-only failure shape (a gateway
 * heartbeating a dead backend) is governed separately by
 * `StreamState.checkKeepaliveOnly`.
 *
 * Budget selection mirrors Sigil #258: while the stream has produced no
 * meaningful event the shorter dead-on-arrival budget applies (when
 * configured); after first content, the full silence budget. The
 * effective budget is re-read every tick, so first content mid-wait
 * widens the remaining allowance. `streamingSilenceTimeoutMs <= 0`
 * disables the watchdog entirely.
 */
object StreamSilenceWatchdog {

  /** Build the monitor loop. The caller starts it on a background
    * fiber and sets `stopped` when the stream terminates; the loop
    * exits on its next tick. Fires at most once.
    *
    * `postContentBudgetMs` is the full silence budget; callers that
    * grant reasoning requests an extended idle window pass the widened
    * value here. `cancel` must be safe to run concurrently with stream
    * consumption (okhttp's `Call.cancel()` is). */
  def run(state: OpenAIChatCompletions.StreamState,
          config: OpenAIChatCompletions.Config,
          postContentBudgetMs: Long,
          preContentBudgetMs: Long,
          cancel: Task[Unit],
          stopped: AtomicBoolean): Task[Unit] = {
    if (postContentBudgetMs <= 0L) return Task.unit
    val pollMs = math.max(50L, math.min(1000L, effectiveMinBudget(postContentBudgetMs, preContentBudgetMs) / 10L))
    // Arm the clock at watchdog start so a stream that never delivers a
    // single line still breaches on time.
    state.lastLineNanos = state.nowNanos()

    def tick: Task[Unit] = Task.sleep(scala.concurrent.duration.FiniteDuration(pollMs, "ms")).flatMap { _ =>
      if (stopped.get()) Task.unit
      else {
        val budget =
          if (state.sawMeaningfulContent || preContentBudgetMs <= 0L) postContentBudgetMs
          else preContentBudgetMs
        val elapsedMs = (state.nowNanos() - state.lastLineNanos) / 1000000L
        if (elapsedMs > budget) {
          state.lineSilenceBreach = Some(
            s"${config.providerName} delivered no stream lines for ${elapsedMs}ms " +
              s"(silence budget ${budget}ms) — upstream is unresponsive."
          )
          cancel.handleError(_ => Task.unit)
        } else tick
      }
    }
    tick
  }

  private def effectiveMinBudget(post: Long, pre: Long): Long =
    if (pre > 0L) math.min(post, pre) else post
}
