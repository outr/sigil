package sigil.signal

import fabric.rw.*
import lightdb.id.Id
import sigil.SpaceId
import sigil.storage.StoredFile

/**
 * Wire-friendly projection of a [[sigil.storage.StoredFile]] —
 * everything a UI / consumer needs to display a file chip without
 * hitting the storage backend. Drops the `path` (an internal-only
 * provider key) and exposes only client-relevant metadata.
 *
 * Used by the stored-file Notice vocabulary
 * ([[StoredFileListSnapshot]], [[StoredFileCreated]],
 * [[StoredFileContent]], [[SaveStoredFile]]) so the wire shape is
 * stable independent of internal `StoredFile` field churn.
 */
case class StoredFileSummary(fileId: Id[StoredFile],
                             space: SpaceId,
                             contentType: String,
                             size: Long,
                             createdMs: Long,
                             modifiedMs: Long,
                             metadata: Map[String, String] = Map.empty) derives RW

object StoredFileSummary {
  def fromStoredFile(file: StoredFile): StoredFileSummary = StoredFileSummary(
    fileId = file._id,
    space = file.space,
    contentType = file.contentType,
    size = file.size,
    createdMs = file.created.value,
    modifiedMs = file.modified.value,
    metadata = file.metadata
  )
}
