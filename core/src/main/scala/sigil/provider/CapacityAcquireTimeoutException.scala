package sigil.provider

import scala.concurrent.duration.FiniteDuration

/**
 * Raised by [[Provider.withCapacity]] when the provider's
 * `capacityGate` semaphore can't acquire a permit within
 * `capacityAcquireTimeout`. Bug #57.
 *
 * Surfaces as a clear fail-fast error path rather than the
 * indefinite thread park `Semaphore.acquire()` would otherwise
 * cause. The agent loop's error handler catches this, publishes a
 * Failure-content Message into the conversation, and releases the
 * agent's claim — same path as any other mid-turn provider error.
 *
 * Indicates either:
 *   - **Permit leak**: a prior call's task never settled, so its
 *     `task.guarantee` release never fired. Search the surrounding
 *     run for a parked Task or interrupted fiber that bypassed the
 *     `guarantee` path.
 *   - **Genuinely concurrent over-capacity**: the deployment is
 *     issuing more concurrent provider calls than `maxConcurrent`
 *     allows AND each call legitimately takes longer than
 *     `capacityAcquireTimeout`. Raise the timeout (override
 *     `Provider.capacityAcquireTimeout`) or scale up
 *     `maxConcurrent`.
 */
final class CapacityAcquireTimeoutException(maxConcurrent: Int, waited: FiniteDuration)
  extends RuntimeException(
    s"capacity gate (maxConcurrent=$maxConcurrent) did not free a permit within ${waited.toMillis}ms — " +
      "permit leak from a parked prior task, or genuinely over-capacity load. " +
      "If this is reproducible without external concurrent load, file with stack-trace + repro steps."
  )
