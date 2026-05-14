package sigil.maintenance

import lightdb.time.Timestamp
import rapid.Task
import sigil.Sigil

import scala.concurrent.duration.*

/**
 * Periodic sweep that hard-deletes every
 * [[sigil.tool.output.ToolOutputNode]] whose `expiresAt` has
 * passed. Reclaims the rows produced by
 * [[sigil.tool.output.PaginatedTool]]'s drain pipeline once
 * their TTL window elapses (default 30 minutes per row).
 *
 * Default cadence: 15 minutes — twice per default TTL window so
 * reclaimed storage doesn't grow unboundedly between sweeps.
 * Apps with longer / shorter retention override
 * [[Sigil.toolOutputExpirationInterval]].
 */
final case class ToolOutputExpirationSweep(interval: FiniteDuration = 15.minutes) extends MaintenanceTask {
  override def name: String = "tool-output-expiration-sweep"

  override def runOnce(host: Sigil): Task[Unit] = {
    val now = Timestamp()
    host.withDB(_.toolOutputs.transaction(_.list)).flatMap { all =>
      val expired = all.toList.filter(_.expiresAt.value <= now.value)
      if (expired.isEmpty) Task.unit
      // Sigil bug #170 — N deletes share one toolOutputs transaction.
      else host.withDB(_.toolOutputs.transaction { tx =>
        Task.sequence(expired.map { n =>
          tx.delete(n._id).unit.handleError { e =>
            Task { scribe.warn(s"ToolOutputExpirationSweep: delete ${n._id.value} failed: ${e.getMessage}"); () }
          }
        }).map { _ =>
          scribe.info(s"ToolOutputExpirationSweep removed ${expired.size} expired record(s)")
        }
      })
    }
  }
}
