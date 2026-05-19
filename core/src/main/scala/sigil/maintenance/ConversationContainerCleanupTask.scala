package sigil.maintenance

import rapid.Task
import sigil.Sigil
import sigil.conversation.Conversation
import sigil.tool.output.ToolOutputNode

import scala.concurrent.duration.*

/**
 * Conversation-level GC for [[sigil.tool.output.ToolOutputNode]]
 * rows. Two pruning triggers:
 *
 *  1. **Age** — containers (the rows grouped by `callId`) whose
 *     `created` timestamp is older than the conversation's most
 *     recent event by more than `ageWindow` get pruned. A
 *     conversation that's "idle since yesterday" with containers
 *     from "a month before that" loses the old containers; new
 *     containers stay until the conversation itself rolls forward
 *     past the window.
 *  2. **Size** — when a conversation's total row count exceeds
 *     `sizeLimit`, prune the oldest unpinned containers in FIFO
 *     order until the row count fits.
 *
 * Pinned rows (`pinned == true`) are never deleted by this pass;
 * apps wire `pin_container` / `unpin_container` for users to mark
 * containers as "do not GC."
 *
 * Coarse-grained — a container created and consumed in the same
 * agent loop never gets reaped (it's new). A container left
 * behind by yesterday's conversation when the user comes back
 * today is still there (well within the window).
 */
final case class ConversationContainerCleanupTask(interval: FiniteDuration = 1.hour,
                                                  ageWindow: FiniteDuration = 30.days,
                                                  sizeLimit: Int = 100000)
  extends MaintenanceTask {

  override def name: String = "conversation-container-cleanup"

  override def runOnce(host: Sigil): Task[Unit] = {
    host.withDB(_.conversations.transaction(_.list)).flatMap { conversations =>
      Task.sequence(conversations.toList.map(c => cleanupOne(host, c))).map { perConvCounts =>
        val total = perConvCounts.sum
        if (total > 0) scribe.info(s"ConversationContainerCleanupTask reclaimed $total tool-output row(s) across ${conversations.size} conversation(s)")
      }
    }
  }

  private def cleanupOne(host: Sigil, conversation: Conversation): Task[Int] = {
    val convId = conversation._id
    host.withDB(_.toolOutputs.transaction(_.list)).flatMap { allRows =>
      val rows = allRows.toList.filter(_.conversationId == convId)
      if (rows.isEmpty) Task.pure(0)
      else mostRecentEventTimestamp(host, conversation).flatMap { lastActivityMillis =>
        val ageCutoffMillis = lastActivityMillis - ageWindow.toMillis
        val aged   = rows.filter(r => !r.pinned && r.created.value < ageCutoffMillis)
        val survivors = rows.filterNot(aged.contains)
        val sizeOverflow = math.max(0, survivors.size - sizeLimit)
        val sizeOverflowVictims: List[ToolOutputNode] = {
          if (sizeOverflow <= 0) Nil
          else {
            // Prune oldest unpinned containers in FIFO order until
            // the row count fits. Group rows by `callId` so we prune
            // whole containers at a time — a tree's children disappear
            // with their root, not piecemeal.
            val unpinned = survivors.filter(!_.pinned)
            val containers = unpinned.groupBy(_.callId).toList.map { case (callId, callRows) =>
              val createdAt = callRows.map(_.created.value).min
              (callId, createdAt, callRows)
            }.sortBy(_._2)
            val builder = scala.collection.mutable.ListBuffer.empty[ToolOutputNode]
            var dropped = 0
            val iter = containers.iterator
            while (iter.hasNext && dropped < sizeOverflow) {
              val (_, _, callRows) = iter.next()
              builder ++= callRows
              dropped += callRows.size
            }
            builder.toList
          }
        }
        val victims = (aged ::: sizeOverflowVictims).distinctBy(_._id)
        if (victims.isEmpty) Task.pure(0)
        else host.withDB(_.toolOutputs.transaction { tx =>
          Task.sequence(victims.map { v =>
            tx.delete(v._id).unit.handleError { e =>
              Task { scribe.warn(s"ConversationContainerCleanupTask: delete ${v._id.value} failed: ${e.getMessage}"); () }
            }
          }).map(_ => victims.size)
        })
      }
    }
  }

  /** Most-recent event timestamp for the conversation; falls back
    * to `conversation.modified` when no events exist (a freshly-
    * minted conversation). Drives the age window so a long-stale
    * conversation's old containers age out while the modified-
    * yesterday conversation's containers stay. */
  private def mostRecentEventTimestamp(host: Sigil, conversation: Conversation): Task[Long] = {
    val convId = conversation._id
    host.withDB(_.events.transaction(_.list)).map { allEvents =>
      val convEvents = allEvents.toList.filter(_.conversationId == convId)
      if (convEvents.isEmpty) conversation.modified.value
      else convEvents.map(_.timestamp.value).max
    }
  }
}
