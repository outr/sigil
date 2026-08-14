package sigil

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.{AtomicInteger, AtomicLong}

/**
 * Accumulates one agent turn's [[TurnPhase]] durations. A single moving
 * cursor divides the turn's wall clock into contiguous segments: each
 * `mark` attributes the elapsed time since the previous mark to the
 * named phase and advances the cursor, so the totals sum to the turn's
 * wall clock no matter which marks fire.
 *
 * Process-local and turn-scoped, exactly like the claim it hangs off.
 */
private[sigil] final class TurnPhaseRecorder(startedAt: Long) {
  private val cursor = new AtomicLong(startedAt)
  private val totals = new ConcurrentHashMap[TurnPhase, java.lang.Long]()
  private val iterationCount = new AtomicInteger(0)
  private val reached = new AtomicInteger(0)

  /**
   * Attribute everything since the previous mark to `phase`.
   *
   * Phases only move forward within an iteration: a mark for a phase the
   * iteration has already passed is attributed to the phase it is
   * currently in. That matters because the framework consumes a provider
   * stream lazily — a tool executing inline pulls the stream's
   * terminator only after the reply has settled, and without this rule
   * that trailing pull would bill the tool's execution back to the
   * model.
   */
  def mark(phase: TurnPhase): Unit = {
    val now = System.currentTimeMillis()
    val current = reached.getAndUpdate(o => math.max(o, phase.ordinal))
    val attributed = if (phase.ordinal >= current) phase else TurnPhase.fromOrdinal(current)
    val previous = cursor.getAndSet(now)
    totals.merge(attributed, java.lang.Long.valueOf(math.max(0L, now - previous)), (a, b) => a + b)
    ()
  }

  /** Note that another agent-loop iteration has begun. */
  def nextIteration(): Unit = {
    iterationCount.incrementAndGet()
    reached.set(0)
    ()
  }

  def iterations: Int = math.max(1, iterationCount.get())

  /** Every phase in temporal order with its accumulated milliseconds. */
  def durations: List[(TurnPhase, Long)] =
    TurnPhase.values.toList.map(p => p -> Option(totals.get(p)).map(_.longValue()).getOrElse(0L))

  def totalMs: Long = cursor.get() - startedAt
}
