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
import sigil.provider.{ProviderConfig, ProviderStrategyRecord, SpaceProviderAssignment}
import sigil.spatial.GeocodingCache
import sigil.storage.StoredFile
import sigil.tool.Tool

import java.nio.file.Path
import scala.concurrent.duration.*

/**
 * Core lightdb wrapper carrying the framework's standard collections.
 *
 * `SigilDB` is open for extension via Scala 3 self-typed mix-in traits.
 * Module sub-projects (e.g. `sigil-secrets`, future `sigil-mcp`) ship a
 * `<X>Collections { self: SigilDB => ... }` trait that adds their own
 * lightdb stores; apps assemble the concrete DB by mixing the traits
 * they want into a single class:
 *
 * {{{
 *   class MyAppDB(directory: Option[Path],
 *                 storeManager: CollectionManager,
 *                 upgrades: List[DatabaseUpgrade] = Nil)
 *     extends SigilDB(directory, storeManager, upgrades)
 *       with SecretsCollections
 *       with McpCollections
 * }}}
 *
 * The Sigil instance refines `type DB = MyAppDB`, satisfying every
 * module's `Sigil { type DB <: SigilDB & XCollections }` bound at once.
 */
abstract class SigilDB(override val directory: Option[Path],
                       override val storeManager: CollectionManager,
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
  val storedFiles: S[StoredFile, StoredFile.type] = store(StoredFile)()
  val providerConfigs: S[ProviderConfig, ProviderConfig.type] = store(ProviderConfig)()
  val providerStrategies: S[ProviderStrategyRecord, ProviderStrategyRecord.type] = store(ProviderStrategyRecord)()
  val providerAssignments: S[SpaceProviderAssignment, SpaceProviderAssignment.type] = store(SpaceProviderAssignment)()

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

/**
 * Concrete `SigilDB` with no module mix-ins. Default for apps that
 * don't need extension collections; the framework's `Sigil` builds
 * an instance of this when `type DB = SigilDB` (the default).
 */
final class DefaultSigilDB(directory: Option[Path],
                           storeManager: CollectionManager,
                           appUpgrades: List[DatabaseUpgrade] = Nil)
  extends SigilDB(directory, storeManager, appUpgrades)
