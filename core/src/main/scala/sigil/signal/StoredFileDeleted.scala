package sigil.signal

import fabric.rw.*
import lightdb.id.Id
import sigil.storage.StoredFile

/**
 * Server→client broadcast Notice fired when a
 * [[sigil.storage.StoredFile]] is deleted. UIs remove the
 * corresponding chip / preview from their "files" panel.
 */
case class StoredFileDeleted(fileId: Id[StoredFile]) extends Notice derives RW
