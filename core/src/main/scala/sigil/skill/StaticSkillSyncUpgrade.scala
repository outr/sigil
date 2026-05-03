package sigil.skill

import lightdb.LightDB
import lightdb.upgrade.DatabaseUpgrade
import rapid.Task
import sigil.db.SigilDB

/**
 * Idempotent startup sync of app-supplied static skills into
 * [[sigil.db.SigilDB.skills]]. Mirrors
 * [[sigil.tool.StaticToolSyncUpgrade]]:
 *   - Newly-added static skills land in the DB.
 *   - Removed static skills' records are pruned (only those with
 *     `createdBy = None` — user-created records survive).
 *   - Modified static skills (description, content, modes, keywords)
 *     have their stored copies overwritten on the next start.
 */
class StaticSkillSyncUpgrade(staticSkills: List[Skill]) extends DatabaseUpgrade {
  override def label: String = "static-skill-sync"
  override def alwaysRun: Boolean = true
  override def applyToNew: Boolean = true
  override def blockStartup: Boolean = true

  override def upgrade(ldb: LightDB): Task[Unit] = ldb match {
    case sigilDb: SigilDB => syncSkills(sigilDb)
    case _                => Task.unit
  }

  private def syncSkills(db: SigilDB): Task[Unit] = {
    val targetNames: Set[String] = staticSkills.map(_.name).toSet
    db.skills.transaction { tx =>
      val upserts = Task.sequence(staticSkills.map(s => tx.upsert(s))).unit
      val prune = tx.list.flatMap { existing =>
        val orphans = existing.filter(s => s.createdBy.isEmpty && !targetNames.contains(s.name))
        Task.sequence(orphans.map(o => tx.delete(o._id))).unit
      }
      upserts.flatMap(_ => prune)
    }
  }
}
