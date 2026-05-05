package sigil.maintenance

import rapid.Task
import sigil.Sigil

import scala.concurrent.duration.FiniteDuration

/**
 * A periodic background task the framework runs on its own fiber for the
 * lifetime of a [[Sigil]] instance. Apps and the framework register
 * tasks via [[Sigil.maintenanceTasks]]; each task gets its own fiber
 * with its own cadence — independent failures, independent intervals.
 *
 * The framework currently ships [[StoredFileExpirationSweep]] for
 * Bug #9's tool-output retention; future expansions (per-Sigil
 * caches, log rotation, schema-upgrade rechecks) plug in by appending
 * their own [[MaintenanceTask]] to the list.
 *
 * Failures during `runOnce` are logged at WARN and swallowed — a
 * transient error doesn't break the loop. The task fires again on
 * the next tick.
 *
 * `runImmediatelyOnStart = true` (the default) means the first run
 * fires right after `Sigil.instance` finishes booting; `false` means
 * the first run waits one full `interval`. Tasks with side effects
 * that should never collide with boot (e.g. heavy I/O) opt for
 * `false`.
 */
trait MaintenanceTask {
  def name: String
  def interval: FiniteDuration
  def runOnce(host: Sigil): Task[Unit]
  def runImmediatelyOnStart: Boolean = true
}
