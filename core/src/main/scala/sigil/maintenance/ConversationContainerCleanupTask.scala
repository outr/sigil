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
 *  1. **Age** (opt-in) — when `ageWindow` is `Some(window)`, containers
 *     (rows grouped by `callId`) whose `created` timestamp is older than
 *     the conversation's most recent event by more than `window` get
 *     pruned. `None` (the default) disables time-based eviction
 *     entirely: tool output is a durable point-in-time observation, not
 *     a regenerable cache, so by default it lives for the conversation's
 *     lifetime and is reclaimed only on conversation delete.
 *  2. **Size** — when a conversation's total row count exceeds
 *     `sizeLimit`, prune the oldest unpinned containers in FIFO order
 *     until the row count fits. A runaway backstop (not a TTL) that
 *     stays active even when age eviction is off.
 *
 * Pinned rows (`pinned == true`) are never deleted by this pass;
 * apps wire `pin_container` / `unpin_container` for users to mark
 * containers as "do not GC."
 */
final case class ConversationContainerCleanupTask(interval: FiniteDuration = 1.hour,
                                                  ageWindow: Option[FiniteDuration] = None,
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
      else {
        // Age pruning is opt-in (ageWindow = Some); when off (the
        // default), skip the most-recent-event query entirely and prune
        // nothing by time — only the size backstop can fire.
        val agedTask: Task[List[ToolOutputNode]] = ageWindow match {
          case None => Task.pure(Nil)
          case Some(window) =>
            mostRecentEventTimestamp(host, conversation).map { lastActivityMillis =>
              val ageCutoffMillis = lastActivityMillis - window.toMillis
              rows.filter(r => !r.pinned && r.created.value < ageCutoffMillis)
            }
        }
        agedTask.flatMap { aged =>
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
  }

  /** Most-recent event timestamp for the conversation; falls back
    * to `conversation.modified` when no events exist (a freshly-
    * minted conversation). Drives the age window so a long-stale
    * conversation's old containers age out while the modified-
    * yesterday conversation's containers stay. */
  private def mostRecentEventTimestamp(host: Sigil, conversation: Conversation): Task[Long] = {
    val convId = conversation._id
    import lightdb.filter.*
    host.withDB(_.events.transaction { tx =>
      // Indexed conversationId narrowing plus a descending timestamp
      // sort — the newest event's timestamp is the first (and only)
      // row read instead of a full-store scan and in-memory max.
      tx.query
        .filter(_ => sigil.event.Event.conversationId === convId.value)
        .sort(lightdb.Sort.ByField(sigil.event.Event.timestamp, lightdb.SortDirection.Descending))
        .limit(1)
        .toList
    }).map {
      case newest :: _ => newest.timestamp.value
      case Nil         => conversation.modified.value
    }
  }
}
