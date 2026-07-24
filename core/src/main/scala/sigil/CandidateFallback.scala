package sigil

import lightdb.id.Id
import rapid.Stream
import sigil.db.Model
import sigil.provider.{ErrorClassification, ErrorClassifier}
import sigil.signal.Signal

/**
 * Sigil #397 — cross-candidate fallback for the live agent turn.
 *
 * The per-provider retry loop ([[sigil.provider.Provider]]'s
 * `callWithTransientRetry`) handles `Retry` by re-running the SAME candidate.
 * When that exhausts (or the error classifies as `Fallthrough`) the error
 * propagates out of the provider — and before #397 it killed the turn even
 * when a perfectly good lower tier was idle. This combinator wraps the turn so
 * a propagated, non-`Fatal` failure ADVANCES to the next routed candidate
 * (degrading the tier within the same turn), records the failed candidate so
 * the strategy's cooldown primes, and only surfaces the error once the
 * candidate list is exhausted.
 *
 * Fallback is safe only BEFORE any signal has been emitted — once the turn has
 * produced observable output (a content delta, a tool call), re-running on
 * another model would duplicate it, so the error propagates instead. An
 * Anthropic overload arrives as an inline `event: error` at stream start,
 * before any output, so the common case falls back cleanly.
 */
object CandidateFallback {

  /**
   * Stream the turn for `candidates` in order, advancing to the next on a
   * pre-commit, non-`Fatal` failure.
   *
   *   - `attempt` runs one candidate's turn (resolve provider+model, build the
   *     request, run the orchestrator).
   *   - `classifier` decides whether a propagated error is terminal (`Fatal` /
   *     `Healable` → surface) or route-elsewhere (`Retry` / `Fallthrough` →
   *     next candidate). `Retry` reaches here only after the provider exhausted
   *     its own same-candidate retries, so it advances rather than loops.
   *   - `reportFailure` primes the strategy's cooldown for a failed candidate.
   *
   * `candidates` must be non-empty (the caller always seeds at least the
   * resolved model). An empty list raises immediately.
   */
  def stream(candidates: List[Id[Model]],
             classifier: ErrorClassifier,
             reportFailure: Id[Model] => Unit,
             /**
              * Sigil #415 — consulted before advancing to the next
              * candidate. A user Stop that lands while one tier is
              * failing must not be answered with a fresh call against
              * the next tier; the error propagates instead and the
              * agent loop's stop handling ends the turn quietly.
              */
             stopRequested: () => Boolean = () => false)(attempt: Id[Model] => Stream[Signal]): Stream[Signal] =
    candidates match {
      case Nil =>
        fail(new IllegalStateException("CandidateFallback.stream requires at least one candidate"))
      case modelId :: rest =>
        val committed = new java.util.concurrent.atomic.AtomicBoolean(false)
        attempt(modelId)
          .flatMap { sig =>
            committed.set(true) // any emitted signal is observable output — no safe re-run after this
            Stream.emit(sig)
          }
          .handleErrorWith { t =>
            if (committed.get() || rest.isEmpty || stopRequested()) fail(t)
            else
              classifier.classify(t) match {
                // Terminal — surface to the agent loop (which heals, or
                // publishes the Failure message). No point trying another tier.
                case ErrorClassification.Fatal | _: ErrorClassification.Healable =>
                  fail(t)
                // Route elsewhere — the candidate is saturated/broken; degrade
                // to the next tier and prime this one's cooldown.
                case _ =>
                  reportFailure(modelId)
                  scribe.warn(
                    s"Sigil #397 — candidate ${modelId.value} failed pre-commit " +
                      s"(${t.getClass.getSimpleName}: ${Option(t.getMessage).getOrElse("")}); " +
                      s"falling back to the next routed candidate"
                  )
                  stream(rest, classifier, reportFailure, stopRequested)(attempt)
              }
          }
    }

  /**
   * Re-raise on pull (matches `Provider.callWithTransientRetry`'s `fail`) so a
   * downstream `onErrorFinalize` / `guarantee` sees it as a stream error.
   */
  private def fail(t: Throwable): Stream[Signal] =
    Stream.emit(()).evalMap[Signal](_ => rapid.Task.error[Signal](t))
}
