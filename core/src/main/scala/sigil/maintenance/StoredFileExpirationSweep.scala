package sigil.maintenance

import lightdb.time.Timestamp
import rapid.Task
import sigil.Sigil
import sigil.storage.StoredFile

import scala.concurrent.duration.*

/**
 * Periodic sweep that hard-deletes every [[StoredFile]] whose
 * `expiresAt` has passed. The bytes (via the configured
 * [[sigil.storage.StorageProvider]]) AND the metadata row are both
 * removed; the absence of the record makes it disappear from
 * retrieval surfaces immediately, the storage reclamation closes the
 * loop on disk usage.
 *
 * Default cadence: 1 hour. Apps with stricter retention windows
 * override [[Sigil.storedFileExpirationInterval]] (or build their
 * own [[MaintenanceTask]] entirely if the sweep needs custom shape
 * — e.g. preserve archived versions).
 */
final case class StoredFileExpirationSweep(interval: FiniteDuration = 1.hour) extends MaintenanceTask {
  override def name: String = "stored-file-expiration-sweep"

  override def runOnce(host: Sigil): Task[Unit] = {
    val now = Timestamp()
    host.withDB(_.storedFiles.transaction(_.list)).flatMap { all =>
      val expired = all.toList.filter(_.isExpired(now))
      if (expired.isEmpty) Task.unit
      else Task.sequence(expired.map { f =>
        // System-level delete: bypass `Sigil.deleteStoredFile`'s
        // authz gate (no caller chain to validate against). Drop
        // the bytes through the storage provider and the metadata
        // row from `SigilDB.storedFiles`.
        host.storageProvider.delete(f.path)
          .flatMap(_ => host.withDB(_.storedFiles.transaction(_.delete(f._id))).unit)
          .handleError { e =>
            Task { scribe.warn(s"StoredFileExpirationSweep: delete ${f._id.value} failed: ${e.getMessage}"); () }
          }
      }).map { _ =>
        scribe.info(s"StoredFileExpirationSweep removed ${expired.size} expired record(s)")
      }
    }
  }
}
