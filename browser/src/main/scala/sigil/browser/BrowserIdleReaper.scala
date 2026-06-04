package sigil.browser

import rapid.Task
import sigil.Sigil
import sigil.maintenance.MaintenanceTask

import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/**
 * Periodic sweep that disposes [[BrowserController]]s idle longer than
 * [[BrowserSigil.browserIdleTimeoutMs]]. Chrome subprocesses are heavy
 * — an abandoned conversation would otherwise hold one open until JVM
 * shutdown.
 *
 * A controller is considered idle when the time since its last
 * `run` call exceeds the configured threshold ([[BrowserController.isIdle]]).
 * Disposal goes through [[BrowserSigil.disposeBrowserController]] so the
 * bound cookie jar is persisted and the [[BrowserState]] settles to
 * `Complete` before the browser is closed.
 *
 * Registered automatically via [[BrowserSigil.maintenanceTasks]]; the
 * framework runs it on its own background fiber.
 */
final case class BrowserIdleReaper(idleTimeoutMs: Long,
                                   interval: FiniteDuration = 30.seconds)
  extends MaintenanceTask {
  override def name: String = "browser-idle-reaper"

  override def runOnce(host: Sigil): Task[Unit] = host match {
    case bs: BrowserSigil =>
      Task.defer {
        val now = System.currentTimeMillis()
        val idle = BrowserSigil.controllers.values().asScala.toList
          .filter(c => !c.isDisposed && c.isIdle(idleTimeoutMs, now))
        if (idle.isEmpty) Task.unit
        else Task.sequence(idle.map { c =>
          bs.disposeBrowserController(c.conversationId)
            .handleError { e =>
              Task {
                scribe.warn(s"BrowserIdleReaper: dispose ${c.conversationId.value} failed: ${e.getMessage}")
                ()
              }
            }
        }).map { _ =>
          scribe.info(s"BrowserIdleReaper disposed ${idle.size} idle controller(s)")
        }
      }
    case _ => Task.unit
  }
}
