package sigil.workflow

import lightdb.LightDB
import lightdb.lucene.LuceneStore
import lightdb.rocksdb.RocksDBStore
import lightdb.store.Collection
import lightdb.store.split.SplitStoreManager
import lightdb.upgrade.DatabaseUpgrade
import strider.Workflow

import java.nio.file.Path

/**
 * Strider-side LightDB hosting the engine's `workflows` collection.
 * Separate from Sigil's main DB so the workflow engine's
 * persistence concerns (crash recovery, step-result history,
 * progress tracking) don't pollute Sigil's collection set.
 *
 * Apps that want a unified DB override [[WorkflowSigil.workflowDb]]
 * to point at their own LightDB — Strider only needs a
 * `Collection[Workflow, AbstractWorkflowModel]` to operate.
 */
final class SigilWorkflowDB(rootDirectory: Option[Path]) extends LightDB {
  override type SM = SplitStoreManager[RocksDBStore.type, LuceneStore.type]
  override val storeManager: SM = SplitStoreManager(RocksDBStore, LuceneStore)

  val workflows: Collection[Workflow, SigilWorkflowModel.type] = store(SigilWorkflowModel)()

  override def directory: Option[Path] = rootDirectory.map(_.resolve("workflows"))
  override def upgrades: List[DatabaseUpgrade] = Nil
}
