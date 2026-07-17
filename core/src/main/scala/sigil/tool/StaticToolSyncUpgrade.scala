package sigil.tool

import fabric.Json
import fabric.rw.*
import lightdb.LightDB
import lightdb.id.Id
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
 *
 * Reads via the store's raw `jsonStream` rather than the typed
 * `list`, so rows whose polytype discriminator names a subtype that
 * was removed in a newer Sigil version (e.g. an old `CancelTool` row
 * left behind after the class was dropped) don't trip the typed-RW
 * dispatch and abort the upgrade. A row that fails to decode is by
 * definition an orphan — no current Scala class can read it — so
 * its `_id` gets pulled from the raw JSON and the row is deleted.
 */
class StaticToolSyncUpgrade(staticTools: List[Tool]) extends DatabaseUpgrade {
  override def label: String = "static-tool-sync"
  override def alwaysRun: Boolean = true
  override def applyToNew: Boolean = true
  override def blockStartup: Boolean = true

  override def upgrade(ldb: LightDB): Task[Unit] = ldb match {
    case sigilDb: SigilDB => syncTools(sigilDb)
    case _ => Task.unit // not our DB shape, skip
  }

  private def syncTools(db: SigilDB): Task[Unit] = {
    val targetNames: Set[String] = staticTools.map(_.name.value).toSet
    val toolRW: RW[Tool] = summon[RW[Tool]]
    db.tools.transaction { tx =>
      // Upsert each static tool — record IS the tool.
      val upserts = Task.sequence(staticTools.map(t => tx.upsert(t))).unit
      // Prune orphan static records. Walk the raw JSON so an
      // obsolete polytype discriminator can't abort the read.
      val prune = tx.jsonStream.toList.flatMap { rows =>
        val toDelete: List[Id[Tool]] = rows.flatMap { json =>
          scala.util.Try(json.as[Tool](using toolRW)) match {
            case scala.util.Success(t) if t.createdBy.isEmpty && !targetNames.contains(t.name.value) =>
              Some(t._id)
            case scala.util.Success(_) =>
              None
            case scala.util.Failure(err) =>
              // Polytype dispatch failed — this row's subtype is no
              // longer registered. Treat as an orphan and pull the
              // id from the raw JSON. The `_id` field is what
              // lightdb persisted; fall back to `name.value` (which
              // is how `Tool._id` is derived) if that field isn't
              // present in the stored shape.
              StaticToolSyncUpgrade.extractOrphanId(json) match {
                case Some(value) =>
                  scribe.warn(
                    s"static-tool-sync: deleting unreadable tool row id=$value " +
                      s"(${err.getClass.getSimpleName}: ${Option(err.getMessage).getOrElse("")})"
                  )
                  Some(Id[Tool](value))
                case None =>
                  scribe.error(
                    s"static-tool-sync: encountered an unreadable tool row with no extractable _id; " +
                      s"leaving in place to avoid silent loss. Underlying error: " +
                      s"${err.getClass.getSimpleName}: ${Option(err.getMessage).getOrElse("")}"
                  )
                  None
              }
          }
        }
        Task.sequence(toDelete.map(id => tx.delete(id))).unit
      }
      upserts.flatMap(_ => prune)
    }
  }
}

object StaticToolSyncUpgrade {

  /**
   * Recover an orphan tool row's id from its raw JSON when typed
   * decoding fails. Lightdb persists `_id` explicitly; the fallback
   * mirrors `Tool._id = Id(name.value)` so older rows that only
   * carry the embedded `name` field still produce an id. Returns
   * `None` only when neither path yields a string.
   */
  def extractOrphanId(json: Json): Option[String] =
    json.get("_id").map(_.asString)
      .orElse(json.get("name").flatMap(_.get("value")).map(_.asString))
}
