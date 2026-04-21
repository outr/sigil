package sigil.db

import lightdb.LightDB
import lightdb.id.Id
import lightdb.lucene.LuceneStore
import lightdb.rocksdb.RocksDBSharedStore
import lightdb.store.CollectionManager
import lightdb.store.split.SplitStoreManager
import lightdb.upgrade.DatabaseUpgrade
import rapid.Task
import sigil.conversation.{ContextMemory, Conversation}
import sigil.event.Event
import sigil.signal.{Delta, Signal}

import java.nio.file.Path

case class SigilDB(directory: Option[Path], storeManager: CollectionManager) extends LightDB {
  override type SM = CollectionManager

  val model: S[Model, Model.type] = store(Model)()
  val events: S[Event, Event.type] = store(Event)()
  val conversations: S[Conversation, Conversation.type] = store(Conversation)()
  val memories: S[ContextMemory, ContextMemory.type] = store(ContextMemory)()

  override def upgrades: List[DatabaseUpgrade] = Nil

  /**
   * Apply a [[Signal]] to the events store:
   *   - `Event` → `insert`
   *   - `Delta` → `get` target, call `delta.apply(target)`, `upsert` the result
   *
   * If a Delta's target doesn't exist (e.g. the orchestrator emitted a delta
   * before its parent Event was persisted, or the parent was deleted), the
   * delta becomes a silent no-op.
   *
   * Apps wire this as a subscriber on the orchestrator's `Stream[Signal]`:
   * {{{
   *   Orchestrator.process(sigil, provider, request).evalTap(sigil.db.applySignal)
   * }}}
   */
  def apply(signal: Signal): Task[Unit] = signal match {
    case e: Event =>
      events.transaction(_.insert(e)).unit
    case d: Delta =>
      events.transaction { tx =>
        tx.get(d.target.asInstanceOf[Id[Event]]).flatMap {
          case Some(target) => tx.upsert(d(target)).unit
          case None         => Task.unit
        }
      }
  }
}
