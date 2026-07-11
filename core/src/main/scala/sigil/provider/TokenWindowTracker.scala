package sigil.provider

import rapid.Task

import java.util.concurrent.ConcurrentLinkedDeque

/**
 * Sliding-window token-usage tracker for the framework's pre-flight
 * rate-limit guard (sigil #283).
 *
 * Tracks `(epochMillis, estimatedTokens)` entries for the past
 * `windowMs` (60 s); given a request's estimated token count, either
 * admits it immediately (current usage + new request fits under the
 * ceiling) or waits the minimum delta for the oldest entry to age out
 * of the window, then re-checks. Pure data-structure — no threads, no
 * background sweep; entries are pruned lazily on every check.
 *
 * One tracker per `(providerKey, modelId)` pair so two providers
 * sharing one upstream account get separate accounting (apps wire
 * cross-provider sharing themselves by handing the same tracker
 * instance to multiple providers; the default per-provider lookup
 * already handles the single-provider case).
 *
 * Distinct from [[RateLimiter]] — that abstraction is for request /
 * minute count pacing (concurrent calls). This tracker is for the
 * model-owner's per-minute INPUT-TOKEN ceiling, which is the relevant
 * binding constraint when an agent fans out many small calls AND when
 * a single large call would dominate the minute's budget.
 */
final class TokenWindowTracker(perMinute: Long,
                               safetyMargin: Double,
                               windowMs: Long = 60_000L) {
  private val entries: ConcurrentLinkedDeque[(Long, Int)] = new ConcurrentLinkedDeque()
  private val ceiling: Long = math.max(1L, (perMinute * safetyMargin).toLong)

  /**
   * Snapshot of current usage in the window (after pruning expired
   * entries). Test-only helper; not used by the framework hot path.
   */
  def usedInWindow: Long = {
    pruneExpired(System.currentTimeMillis())
    snapshotSum
  }

  /**
   * Admit a request of size `tokens`. Returns a Task that completes
   * when the request is allowed to proceed; the tracker records the
   * tokens against the current window at completion.
   *
   * Behavior:
   *
   *   - If `tokens >= ceiling`: returns immediately without
   *     recording — a single request that big can never fit the
   *     per-minute budget, and the pre-flight gate above us has
   *     already raised [[RequestExceedsRateLimitException]] in that
   *     case. The tracker's job is window pacing for requests that
   *     individually fit; oversized-single-request rejection is the
   *     gate's job.
   *   - If `used + tokens <= ceiling`: records and returns.
   *   - Otherwise: sleeps the minimum delta for the oldest entry to
   *     age out of the window, then re-checks. Tail-recursive via
   *     rapid `flatMap`.
   *
   * Note that recording happens BEFORE the underlying HTTP call
   * completes — we use the curator-estimated token count as a
   * conservative reservation. Apps that want token-accurate tracking
   * post-hoc can wire a settled-effect callback to adjust the entry
   * after the provider's `Usage` event arrives, but the default
   * window-arithmetic does not require that level of precision —
   * estimation overshoot is the safer side of the budget.
   */
  def admit(tokens: Int): Task[Unit] = Task.defer {
    if (tokens >= ceiling) Task.unit
    else admitLoop(tokens)
  }

  private def admitLoop(tokens: Int): Task[Unit] = Task.defer {
    val now = System.currentTimeMillis()
    pruneExpired(now)
    val used = snapshotSum
    if (used + tokens <= ceiling) {
      entries.add((now, tokens))
      Task.unit
    } else {
      val oldest = Option(entries.peekFirst()).map(_._1).getOrElse(now)
      val waitMs = math.max(50L, (oldest + windowMs) - now + 25L) // 25ms slack so the prune sees the entry expired
      Task.sleep(scala.concurrent.duration.FiniteDuration(waitMs, "millis")).flatMap(_ => admitLoop(tokens))
    }
  }

  private def pruneExpired(now: Long): Unit = {
    val cutoff = now - windowMs
    while ({
      val head = entries.peekFirst()
      head != null && head._1 < cutoff
    }) entries.pollFirst()
  }

  private def snapshotSum: Long = {
    var sum = 0L
    val it = entries.iterator()
    while (it.hasNext) sum += it.next()._2
    sum
  }
}
