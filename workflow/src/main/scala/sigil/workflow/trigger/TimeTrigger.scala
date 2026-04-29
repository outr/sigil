package sigil.workflow.trigger

import fabric.{Json, Null, obj, num, str}
import fabric.rw.*
import lightdb.id.Id
import rapid.Task
import sigil.Sigil
import sigil.workflow.WorkflowTrigger
import strider.Workflow
import strider.step.{Step, Trigger, TriggerMode}

/**
 * Workflow trigger that fires on a recurring schedule.
 *
 * Specified as either:
 *
 *   - `intervalMs` — fire every N milliseconds from registration
 *   - `cron` — fire when the cron expression matches (5-field
 *     `minute hour day-of-month month day-of-week` form)
 *
 * Exactly one of the two must be set. If both or neither are set,
 * registration fails with a clear error.
 *
 * Pair with `mode = "branch"` on the enclosing
 * [[sigil.workflow.TriggerStepInput]] for the typical "run a clone
 * of this workflow at every tick" shape — the workflow's earlier
 * steps don't re-run.
 */
final case class TimeTrigger(intervalMs: Option[Long] = None,
                             cron: Option[String] = None)
  extends WorkflowTrigger derives RW {

  override def kind: String = TimeTrigger.Kind

  override def compile(host: Sigil): Trigger = TimeTriggerImpl(this)
}

object TimeTrigger {
  val Kind: String = "time"
}

/** Strider-side time trigger. `register` records the activation
  * timestamp; `check` returns the next scheduled fire when the
  * current wall clock has caught up. */
final case class TimeTriggerImpl(spec: TimeTrigger,
                                 id: Id[Step] = Step.id()) extends Trigger derives RW {
  override def name: String = "Time"
  override def mode: TriggerMode =
    if (spec.intervalMs.isDefined || spec.cron.isDefined) TriggerMode.Branch
    else TriggerMode.Continue

  @transient @volatile private var registeredAt: Long = 0L
  @transient @volatile private var lastFiredAt: Long = 0L

  override def register(workflow: Workflow): Task[Json] = Task {
    if (spec.intervalMs.isEmpty && spec.cron.isEmpty)
      throw new IllegalArgumentException(s"TimeTrigger must specify either intervalMs or cron")
    if (spec.intervalMs.isDefined && spec.cron.isDefined)
      throw new IllegalArgumentException(s"TimeTrigger must specify only one of intervalMs / cron, not both")
    registeredAt = System.currentTimeMillis()
    lastFiredAt = registeredAt
    obj("registeredAt" -> num(registeredAt))
  }

  override def check(workflow: Workflow): Task[Option[Json]] = Task {
    val now = System.currentTimeMillis()
    spec.intervalMs match {
      case Some(interval) if now - lastFiredAt >= interval =>
        lastFiredAt = now
        Some(obj("firedAt" -> num(now)): Json)
      case _ =>
        spec.cron match {
          case Some(expr) if cronMatches(expr, now, lastFiredAt) =>
            lastFiredAt = now
            Some(obj("firedAt" -> num(now), "cron" -> str(expr)): Json)
          case _ => None
        }
    }
  }

  override def unregister(workflow: Workflow): Task[Unit] = Task.unit

  /** Minimal cron evaluator — matches the current minute against
    * the pattern, suppressing duplicate fires within the same
    * minute. Supports `*` and comma-separated lists per field;
    * ranges and steps fall through to "no match" for now. Apps
    * needing richer cron semantics override the trigger. */
  private def cronMatches(expr: String, nowMs: Long, lastMs: Long): Boolean = {
    if ((nowMs / 60_000L) == (lastMs / 60_000L)) return false
    val cal = java.util.Calendar.getInstance()
    cal.setTimeInMillis(nowMs)
    val parts = expr.trim.split("\\s+")
    if (parts.length != 5) return false
    def matchField(f: String, v: Int): Boolean =
      f == "*" || f.split(",").map(_.trim).contains(v.toString)
    matchField(parts(0), cal.get(java.util.Calendar.MINUTE)) &&
      matchField(parts(1), cal.get(java.util.Calendar.HOUR_OF_DAY)) &&
      matchField(parts(2), cal.get(java.util.Calendar.DAY_OF_MONTH)) &&
      matchField(parts(3), cal.get(java.util.Calendar.MONTH) + 1) &&
      matchField(parts(4), {
        val dow = cal.get(java.util.Calendar.DAY_OF_WEEK)
        if (dow == java.util.Calendar.SUNDAY) 0 else dow - 1
      })
  }
}
