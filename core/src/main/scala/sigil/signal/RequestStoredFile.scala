package sigil.signal

import fabric.rw.*
import lightdb.id.Id
import sigil.storage.StoredFile

/**
 * Client→server pull for the bytes of a specific
 * [[sigil.storage.StoredFile]]. The server replies with a
 * [[StoredFileContent]] carrying the base64-encoded payload (or
 * drops the request if the caller's chain isn't authorized for the
 * file's space).
 *
 * UIs typically issue this when the user clicks a file chip or
 * opens an inline editor on a `StoredFileReference`.
 */
case class RequestStoredFile(fileId: Id[StoredFile]) extends Notice derives RW
