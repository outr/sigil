package sigil.db

import lightdb.id.Id
import sigil.conversation.Conversation
import sigil.event.Event

import java.util.{LinkedHashMap => JLinkedHashMap, Map => JMap}
import scala.jdk.CollectionConverters.*

/**
 * Bounded in-process window of the most recently written [[Event]]s per
 * conversation, maintained by [[SigilDB.write]] on every persisted write.
 *
 * It exists to close the freshness gap between the two halves of the split
 * store. A whole-store `_.list` reads the key-value side and is immediately
 * consistent with everything written so far, including writes made inside the
 * transaction doing the reading. An indexed query reads a search index whose
 * reader is materialized once per transaction and never refreshed while that
 * transaction stays open — so a long-lived scope
 * ([[SigilDB.withBatchedEvents]], one per agent-loop iteration) sees none of
 * its own writes, and a read on such a scope also misses anything a
 * concurrently-committed transaction wrote after the reader was taken.
 * Merging this window over an indexed result restores the read-your-writes
 * semantics the `_.list` scan had, without loading every event of every
 * conversation to get it.
 *
 * Bounded on both axes so a long-lived process can't grow it without limit:
 * at most `eventsPerConversation` events per conversation (oldest evicted
 * first) across at most `trackedConversations` conversations (least-recently
 * used evicted first). Falling off either edge is safe — the indexed read
 * still returns those rows, since anything that old has long since become
 * visible to a fresh search reader.
 */
final class RecentEventOverlay(eventsPerConversation: Int, trackedConversations: Int) {

  private final class Window {
    private val entries: JLinkedHashMap[Id[Event], Event] =
      new JLinkedHashMap[Id[Event], Event](16, 0.75f, false) {
        override def removeEldestEntry(eldest: JMap.Entry[Id[Event], Event]): Boolean = size() > eventsPerConversation
      }

    /** Last-write-wins on id, so a Delta's post-application row replaces the
      * version this window already held rather than adding an entry. */
    def record(event: Event): Unit = synchronized {
      entries.put(event._id, event)
      ()
    }

    def snapshot: List[Event] = synchronized(entries.values().iterator().asScala.toList)
  }

  private val windows: JLinkedHashMap[Id[Conversation], Window] =
    new JLinkedHashMap[Id[Conversation], Window](16, 0.75f, true) {
      override def removeEldestEntry(eldest: JMap.Entry[Id[Conversation], Window]): Boolean = size() > trackedConversations
    }

  def record(conversationId: Id[Conversation], event: Event): Unit = {
    val window = windows.synchronized {
      windows.get(conversationId) match {
        case null =>
          val fresh = new Window
          windows.put(conversationId, fresh)
          fresh
        case existing => existing
      }
    }
    window.record(event)
  }

  /** Point-in-time snapshot for `conversationId`, oldest first. */
  def recent(conversationId: Id[Conversation]): List[Event] =
    windows.synchronized(Option(windows.get(conversationId))) match {
      case Some(window) => window.snapshot
      case None         => Nil
    }

  /** Drop everything held for `conversationId`. Called whenever the durable
    * rows go away or move — a deleted conversation, a staging conversation
    * merged into its target — so the window can't resurrect a row that no
    * longer exists under that id. */
  def forget(conversationId: Id[Conversation]): Unit = windows.synchronized {
    windows.remove(conversationId)
    ()
  }
}
