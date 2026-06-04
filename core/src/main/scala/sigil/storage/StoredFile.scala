package sigil.storage

import fabric.rw.*
import lightdb.doc.{JsonConversion, RecordDocument, RecordDocumentModel}
import lightdb.id.Id
import lightdb.time.Timestamp
import rapid.Unique
import sigil.SpaceId

/**
 * Database record tracking bytes written through a [[StorageProvider]].
 * The bytes themselves live wherever the provider puts them; this
 * record carries the metadata Sigil needs to authorize, list, and
 * resolve files.
 *
 * **`SpaceId` scoping is the framework's tenant boundary.** Reads,
 * lists, and deletes are filtered through `Sigil.accessibleSpaces(chain)`
 * — a caller whose chain doesn't authorize the file's `space` cannot
 * fetch its bytes. The single-assignment rule applies: a file
 * carries exactly one space; copy the record to expose it under
 * another space.
 *
 * `path` is the provider's opaque key — derived as `<space.value>/<id>`
 * by `Sigil.storeBytes` so backends with hierarchical listing keep
 * tenant directories naturally separated, but providers MUST treat
 * it as opaque.
 */
case class StoredFile(space: SpaceId,
                      path: String,
                      contentType: String,
                      size: Long,
                      category: StoredFileCategory = StoredFileCategory.UserAttachment,
                      expiresAt: Option[Timestamp] = None,
                      created: Timestamp = Timestamp(),
                      modified: Timestamp = Timestamp(),
                      metadata: Map[String, String] = Map.empty,
                      _id: Id[StoredFile] = StoredFile.id())
  extends RecordDocument[StoredFile] {

  /**
   * True when `expiresAt` is set and not in the future. Used by
   * [[sigil.maintenance.StoredFileExpirationSweep]] and by retrieval
   * filters to skip records past their retention window.
   */
  def isExpired(now: Timestamp): Boolean =
    expiresAt.exists(_.value <= now.value)
}

object StoredFile extends RecordDocumentModel[StoredFile] with JsonConversion[StoredFile] {
  implicit override def rw: RW[StoredFile] = RW.gen

  override def id(value: String = Unique()): Id[StoredFile] = Id(value)

  /**
   * Tenant-key index — string form of `SpaceId.value` so authz
   * filters can `space === "<value>"` without matching the trait
   * itself.
   */
  val spaceKey: I[String] = field.index("spaceKey", _.space.value)
  val path: I[String] = field.index(_.path)
  val createdAt: I[Long] = field.index("createdAt", _.created.value)

  /**
   * Filter index — apps' "files panel" UIs default to
   * `categoryKey === UserAttachment` to hide tool-output noise.
   */
  val categoryKey: I[String] = field.index("categoryKey", _.category.toString)

  /**
   * Expiration index — the maintenance sweep queries
   * `expiresAtKey <= now` to find every reclaimable record in one
   * pass. Records without expiry get `Long.MaxValue` so they sort
   * past any real retention horizon.
   */
  val expiresAtKey: I[Long] = field.index("expiresAtKey", _.expiresAt.map(_.value).getOrElse(Long.MaxValue))
}
