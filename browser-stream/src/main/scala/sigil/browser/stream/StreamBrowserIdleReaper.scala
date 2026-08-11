package sigil.browser.stream

import rapid.Task
import sigil.Sigil
import sigil.maintenance.MaintenanceTask

import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/**
 * Periodic sweep that disposes [[StreamBrowserController]]s idle longer
 * than [[StreamBrowserSigil.streamBrowserIdleTimeoutMs]]. A stream
 * browser costs a headful Chrome plus an Xvfb display, so an abandoned
 * preview is more expensive than an abandoned automation browser.
 *
 * A controller with a live [[PreviewStreamSession]] is never idle, so
 * this only reaps browsers nobody is watching.
 *
 * Registered automatically via [[StreamBrowserSigil.maintenanceTasks]].
 */
final case class StreamBrowserIdleReaper(idleTimeoutMs: Long,
                                         interval: FiniteDuration = 30.seconds) extends MaintenanceTask {
  override def name: String = "stream-browser-idle-reaper"

  override def runOnce(host: Sigil): Task[Unit] = host match {
    case sbs: StreamBrowserSigil =>
      Task.defer {
        val now = System.currentTimeMillis()
        val idle = StreamBrowserSigil.controllers.values().asScala.toList
          .filter(c => !c.isDisposed && c.isIdle(idleTimeoutMs, now))
        if (idle.isEmpty) Task.unit
        else Task.sequence(idle.map { c =>
          sbs.disposeStreamBrowserController(c.conversationId).handleError { e =>
            Task {
              scribe.warn(s"StreamBrowserIdleReaper: dispose ${c.conversationId.value} failed: ${e.getMessage}")
              ()
            }
          }
        }).map { _ =>
          scribe.info(s"StreamBrowserIdleReaper disposed ${idle.size} idle stream browser(s)")
        }
      }
    case _ => Task.unit
  }
}
