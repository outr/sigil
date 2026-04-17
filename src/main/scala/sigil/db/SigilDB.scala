package sigil.db

import lightdb.LightDB
import lightdb.lucene.LuceneStore
import lightdb.rocksdb.RocksDBSharedStore
import lightdb.store.split.SplitStoreManager
import lightdb.upgrade.DatabaseUpgrade

import java.nio.file.Path

case class SigilDB(directory: Option[Path]) extends LightDB {
  private type TM = RocksDBSharedStore
  override type SM = SplitStoreManager[TM, LuceneStore.type]

  private val traversalManager: TM = RocksDBSharedStore(directory.get)
  override val storeManager: SM = SplitStoreManager(traversalManager, LuceneStore)
  
  val model: S[Model, Model.type] = store(Model)

  override def upgrades: List[DatabaseUpgrade] = Nil
}
