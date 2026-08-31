package sigil.provider

/**
 * Slot gate for live provider streams with two admission classes.
 *
 * A plain fair semaphore treats an interactive agent-frame stream and
 * a batch consult identically: a sustained batch fan-out keeps the
 * queue full and an interactive turn waits behind the whole backlog.
 * This gate keeps the semaphore's guarantees — at most [[permits]]
 * concurrent holders, FIFO within a class, stop-aware bounded waits —
 * and adds:
 *
 *   - **Interactive priority.** When a permit frees, queued
 *     INTERACTIVE waiters are granted before queued batch waiters,
 *     however long the batch waiters have been in line. Batch alone
 *     (no interactive demand) still uses ALL permits.
 *   - **Batch hold** ([[holdBatch]] / [[releaseBatchHold]]). While any
 *     hold is active, NEW batch admissions block even when permits are
 *     free; in-flight streams are unaffected. The silence watchdog
 *     engages this when an admitted stream is being starved
 *     server-side ([[StreamStarvationRelief]]): pausing fresh batch
 *     arrivals drains the backend toward a free slot so its scheduler
 *     finally serves the starved task. Holds count — concurrent
 *     stalls compose.
 *
 * Waits poll `stopRequested` every 250ms so a queued call whose
 * conversation the user stopped abandons the line without consuming a
 * permit.
 */
final class StreamSlotGate(val permits: Int) {
  import StreamSlotGate.Outcome

  final private class Waiter {
    var granted: Boolean = false
    var abandoned: Boolean = false
  }

  private val lock = new Object
  private var available: Int = permits
  private var batchHolds: Int = 0
  private val interactiveQueue = new java.util.ArrayDeque[Waiter]()
  private val batchQueue = new java.util.ArrayDeque[Waiter]()

  /**
   * Acquire a slot. Blocks (virtual-thread friendly) until granted,
   * `stopRequested` turns true, or `timeoutMs` passes.
   */
  def acquire(interactive: Boolean, stopRequested: () => Boolean, timeoutMs: Long): Outcome = {
    val deadline = System.currentTimeMillis() + timeoutMs
    val waiter = new Waiter
    lock.synchronized {
      val canTakeNow =
        available > 0 && (
          if (interactive) interactiveQueue.isEmpty
          else batchHolds == 0 && interactiveQueue.isEmpty && batchQueue.isEmpty
        )
      if (canTakeNow) {
        available -= 1
        return Outcome.Acquired
      }
      (if (interactive) interactiveQueue else batchQueue).addLast(waiter)
      while (!waiter.granted) {
        if (stopRequested()) {
          removeOrHandBack(waiter, interactive)
          return Outcome.Stopped
        }
        if (System.currentTimeMillis() > deadline) {
          removeOrHandBack(waiter, interactive)
          return Outcome.TimedOut
        }
        lock.wait(250L)
      }
      Outcome.Acquired
    }
  }

  /**
   * Release a held slot — grants the next eligible waiter, interactive
   * first. Must be called exactly once per successful [[acquire]].
   */
  def release(): Unit = lock.synchronized {
    available += 1
    grantEligible()
  }

  /**
   * Block NEW batch admissions until the matching
   * [[releaseBatchHold]]. In-flight streams and interactive
   * admissions are unaffected. Counting — concurrent holds nest.
   */
  def holdBatch(): Unit = lock.synchronized {
    batchHolds += 1
  }

  def releaseBatchHold(): Unit = lock.synchronized {
    if (batchHolds > 0) batchHolds -= 1
    if (batchHolds == 0) grantEligible()
  }

  /**
   * Snapshot of free permits — diagnostics only.
   */
  def availablePermits: Int = lock.synchronized(available)

  /**
   * Grant free permits to queued waiters: every interactive waiter
   * first, then batch (only while no hold is active). Runs under
   * [[lock]].
   */
  private def grantEligible(): Unit = {
    var granted = false
    while (available > 0 && !interactiveQueue.isEmpty) {
      val w = interactiveQueue.pollFirst()
      if (!w.abandoned) {
        w.granted = true
        available -= 1
        granted = true
      }
    }
    while (available > 0 && batchHolds == 0 && interactiveQueue.isEmpty && !batchQueue.isEmpty) {
      val w = batchQueue.pollFirst()
      if (!w.abandoned) {
        w.granted = true
        available -= 1
        granted = true
      }
    }
    if (granted) lock.notifyAll()
  }

  /**
   * A waiter abandoning the line (stop / timeout). If a grant raced
   * the abandonment, hand the permit straight back so it isn't
   * leaked; otherwise mark the queue entry dead for `grantEligible`
   * to skip. Runs under [[lock]].
   */
  private def removeOrHandBack(waiter: Waiter, interactive: Boolean): Unit =
    if (waiter.granted) {
      available += 1
      grantEligible()
    } else {
      waiter.abandoned = true
      (if (interactive) interactiveQueue else batchQueue).remove(waiter)
    }
}

object StreamSlotGate {

  /**
   * Why an [[StreamSlotGate.acquire]] returned without a permit — or
   * that it got one.
   */
  enum Outcome {
    case Acquired
    case TimedOut
    case Stopped
  }
}
