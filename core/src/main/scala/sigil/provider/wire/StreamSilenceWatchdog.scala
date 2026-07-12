package sigil.provider.wire

import rapid.Task
import sigil.provider.StreamStarvationRelief

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Timer-enforced silence guard for streaming provider calls. EVERY
 * silence budget is enforced here on wall-clock time — a lazy check
 * that only evaluates when a line arrives inherits the line cadence
 * (observed live firing at 8.8× and then 5.2× its configured budget)
 * and never fires at all for a stream that stops receiving lines.
 *
 * Two independent clocks per tick:
 *
 *   - **True line-silence** (`postContentBudgetMs` / the shorter
 *     `preContentBudgetMs` before first content, Sigil #258): no line
 *     of ANY kind within the budget → the upstream is dead. Keepalive
 *     lines reset this clock.
 *   - **Keepalive-only** (`keepaliveOnlyBudgetMs`): lines may keep
 *     arriving but nothing meaningful has ever been produced within
 *     the budget — a gateway heartbeating a dead backend, or a
 *     backend scheduler starving an admitted request. Armed by the
 *     stream's first line; meaningful events reset the anchor.
 *
 * On breach the watchdog records the diagnostic on the
 * [[OpenAIChatCompletions.StreamState]] and cancels the underlying
 * HTTP call; `closeStream` surfaces the typed `upstream_silent`
 * exception on the stream's own termination path.
 *
 * **Starvation relief** (`relief` + `reliefMs`): well before the
 * keepalive-only budget kills the stream, a stall is signalled to the
 * provider's slot gate so it pauses NEW batch admissions — the backend
 * drains toward a free slot and its scheduler finally serves the
 * starved stream. The stall clears when meaningful content arrives,
 * the budget breaches, or the stream terminates; every stall is paired
 * with exactly one clear.
 */
object StreamSilenceWatchdog {

  /** Build the monitor loop. The caller starts it on a background
    * fiber and sets `stopped` when the stream terminates; the loop
    * exits on its next tick (clearing any active stall). Breaches fire
    * at most once.
    *
    * `postContentBudgetMs` is the full line-silence budget; callers
    * that grant reasoning requests an extended idle window pass the
    * widened value here. `cancel` must be safe to run concurrently
    * with stream consumption (okhttp's `Call.cancel()` is). */
  def run(state: OpenAIChatCompletions.StreamState,
          config: OpenAIChatCompletions.Config,
          postContentBudgetMs: Long,
          preContentBudgetMs: Long,
          keepaliveOnlyBudgetMs: Long,
          reliefMs: Long,
          relief: Option[StreamStarvationRelief],
          cancel: Task[Unit],
          stopped: AtomicBoolean): Task[Unit] = {
    val lineBudgetOn = postContentBudgetMs > 0L
    val keepaliveBudgetOn = keepaliveOnlyBudgetMs > 0L
    val reliefOn = relief.isDefined && reliefMs > 0L
    if (!lineBudgetOn && !keepaliveBudgetOn && !reliefOn) return Task.unit
    val pollMs = {
      val budgets = List(
        if (lineBudgetOn) Some(effectiveMinBudget(postContentBudgetMs, preContentBudgetMs)) else None,
        if (keepaliveBudgetOn) Some(keepaliveOnlyBudgetMs) else None,
        if (reliefOn) Some(reliefMs) else None
      ).flatten
      math.max(50L, math.min(1000L, budgets.min / 10L))
    }
    // Arm the line clock at watchdog start so a stream that never
    // delivers a single line still breaches on time.
    state.lastLineNanos = state.nowNanos()

    def breach(diagnostic: String, stalled: Boolean): Task[Unit] = Task.defer {
      state.lineSilenceBreach = Some(diagnostic)
      if (stalled) relief.foreach(_.clear())
      cancel.handleError(_ => Task.unit)
    }

    def tick(stalled: Boolean): Task[Unit] =
      Task.sleep(scala.concurrent.duration.FiniteDuration(pollMs, "ms")).flatMap { _ =>
        if (stopped.get()) Task {
          if (stalled) relief.foreach(_.clear())
        }
        else {
          val now = state.nowNanos()
          // Keepalive-only clock — armed once the stream's first line
          // arrived; meaningful events reset the anchor.
          val kaElapsedMs: Option[Long] =
            if (state.lastMeaningfulNanos < 0L) None
            else Some((now - state.lastMeaningfulNanos) / 1000000L)
          val lineElapsedMs = (now - state.lastLineNanos) / 1000000L
          val lineBudget =
            if (state.sawMeaningfulContent || preContentBudgetMs <= 0L) postContentBudgetMs
            else preContentBudgetMs

          if (keepaliveBudgetOn && kaElapsedMs.exists(_ > keepaliveOnlyBudgetMs))
            breach(
              s"${config.providerName} emitted only keepalive chunks for ${kaElapsedMs.get}ms " +
                s"(keepalive-only budget ${keepaliveOnlyBudgetMs}ms) — the connection is alive " +
                "but the backend has produced nothing.",
              stalled
            )
          else if (lineBudgetOn && lineElapsedMs > lineBudget)
            breach(
              s"${config.providerName} delivered no stream lines for ${lineElapsedMs}ms " +
                s"(silence budget ${lineBudget}ms) — upstream is unresponsive.",
              stalled
            )
          else if (reliefOn) {
            val starving = kaElapsedMs.exists(_ > reliefMs)
            if (starving && !stalled) {
              relief.foreach(_.stall())
              tick(stalled = true)
            } else if (!starving && stalled) {
              // Meaningful content arrived — the stream is being served.
              relief.foreach(_.clear())
              tick(stalled = false)
            } else tick(stalled)
          }
          else tick(stalled)
        }
      }
    tick(stalled = false)
  }

  private def effectiveMinBudget(post: Long, pre: Long): Long =
    if (pre > 0L) math.min(post, pre) else post
}
