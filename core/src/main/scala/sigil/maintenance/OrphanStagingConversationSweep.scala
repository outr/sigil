package sigil.maintenance

import lightdb.time.Timestamp
import rapid.Task
import sigil.Sigil
import sigil.conversation.Conversation

import scala.concurrent.duration.*

/**
 * Periodic sweep that GC's [[Conversation]] rows marked as
 * staging areas (`stagingFor.nonEmpty`) older than `cutoff` —
 * the assumption being that any in-progress import workflow
 * would have completed (and called
 * [[Sigil.mergeStagingIntoMain]] or
 * [[Sigil.deleteStagingConversation]]) within that window.
 *
 * Backstop for crashes / kill-9s / Strider runtime failures
 * that bypassed the workflow body's explicit cleanup. The
 * happy path (workflow completes cleanly OR cancel signal
 * fires the body's cleanup branch) reaps the staging conv
 * proactively.
 *
 * Default cadence: 1 hour. Default cutoff: 24 hours — generous
 * for legit long imports while still bounded.
 *
 * Apps that run unusually long imports (multi-day) should
 * extend [[Sigil.maintenanceTasks]] with their own instance
 * configured with a longer cutoff, or override
 * [[Sigil.orphanStagingCutoff]] / [[Sigil.orphanStagingSweepInterval]].
 */
final case class OrphanStagingConversationSweep(interval: FiniteDuration = 1.hour,
                                                cutoff: FiniteDuration = 24.hours) extends MaintenanceTask {
  override def name: String = "orphan-staging-conversation-sweep"

  override def runOnce(host: Sigil): Task[Unit] = {
    val ageOutMillis = System.currentTimeMillis() - cutoff.toMillis
    host.withDB(_.conversations.transaction { tx =>
      tx.query
        .filter(c => c.stagingFor !== None)
        .filter(_.createdAt < ageOutMillis)
        .toList
    }).flatMap { stale =>
      if (stale.isEmpty) Task.unit
      else Task.sequence(stale.map { conv =>
        host.deleteStagingConversation(conv._id).handleError { e =>
          Task {
            scribe.warn(s"OrphanStagingConversationSweep: delete ${conv._id.value} failed: ${e.getMessage}"); ()
          }
        }
      }).map { _ =>
        scribe.info(s"OrphanStagingConversationSweep removed ${stale.size} abandoned staging conversation(s)")
      }
    }
  }
}
