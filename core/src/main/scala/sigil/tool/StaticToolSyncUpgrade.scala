package sigil.tool

import lightdb.LightDB
import lightdb.upgrade.DatabaseUpgrade
import rapid.Task
import sigil.db.SigilDB

/**
 * Idempotent startup sync of app-supplied static tools into
 * [[sigil.db.SigilDB.tools]]. Runs every startup
 * (`alwaysRun = true, blockStartup = true`) so:
 *   - Newly-added static tools land in the DB.
 *   - Removed static tools' records are pruned (only those with
 *     `createdBy = None` — user-created records survive).
 *   - Modified static tools (description, modes, spaces, keywords)
 *     have their stored copies overwritten on the next start.
 */
class StaticToolSyncUpgrade(staticTools: List[Tool]) extends DatabaseUpgrade {
  override def label: String = "static-tool-sync"
  override def alwaysRun: Boolean = true
  override def applyToNew: Boolean = true
  override def blockStartup: Boolean = true

  override def upgrade(ldb: LightDB): Task[Unit] = ldb match {
    case sigilDb: SigilDB => syncTools(sigilDb)
    case _                => Task.unit  // not our DB shape, skip
  }

  private def syncTools(db: SigilDB): Task[Unit] = {
    val targetNames: Set[String] = staticTools.map(_.name.value).toSet
    db.tools.transaction { tx =>
      // Upsert each static tool — record IS the tool.
      val upserts = Task.sequence(staticTools.map(t => tx.upsert(t))).unit
      // Prune orphan static records: createdBy = None AND name not in current set.
      val prune = tx.list.flatMap { existing =>
        val orphans = existing.filter(t => t.createdBy.isEmpty && !targetNames.contains(t.name.value))
        Task.sequence(orphans.map(o => tx.delete(o._id))).unit
      }
      upserts.flatMap(_ => prune)
    }
  }
}
