package sigil.db

import lightdb.LightDB
import lightdb.cache.CacheConfig
import lightdb.id.Id
import lightdb.store.CollectionManager
import lightdb.upgrade.DatabaseUpgrade
import rapid.Task
import sigil.conversation.{ContextMemory, ContextSummary, Conversation, ConversationView, Topic}
import sigil.event.Event
import sigil.signal.{Delta, Signal}
import sigil.spatial.GeocodingCache
import sigil.tool.Tool

import java.nio.file.Path
import scala.concurrent.duration.*

case class SigilDB(directory: Option[Path],
                   storeManager: CollectionManager,
                   appUpgrades: List[DatabaseUpgrade] = Nil) extends LightDB {
  override type SM = CollectionManager

  val events: S[Event, Event.type] = store(Event)()
  val conversations: S[Conversation, Conversation.type] = store(Conversation).withCache(CacheConfig.lru(1000))()
  val memories: S[ContextMemory, ContextMemory.type] = store(ContextMemory).withCache(CacheConfig.lru(500, 5.minutes))()
  val summaries: S[ContextSummary, ContextSummary.type] = store(ContextSummary).withCache(CacheConfig.lru(500, 5.minutes))()
  val views: S[ConversationView, ConversationView.type] = store(ConversationView).withCache(CacheConfig.lru(1000))()
  val topics: S[Topic, Topic.type] = store(Topic).withCache(CacheConfig.lru(2000))()
  val geocodingCache: S[GeocodingCache, GeocodingCache.type] = store(GeocodingCache)()
  val tools: S[Tool, Tool.type] = store(Tool).withCache(CacheConfig.lru(500))()

  override def upgrades: List[DatabaseUpgrade] = appUpgrades

  /**
   * Apply a [[Signal]] to the events store.
   */
  def apply(signal: Signal): Task[Unit] = signal match {
    case e: Event =>
      events.transaction(_.insert(e)).unit
    case d: Delta =>
      events.transaction { tx =>
        tx.get(d.target.asInstanceOf[Id[Event]]).flatMap {
          case Some(target) => tx.upsert(d(target)).unit
          case None => Task.unit
        }
      }
  }
}
