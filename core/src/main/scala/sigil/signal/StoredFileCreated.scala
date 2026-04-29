package sigil.signal

import fabric.rw.*

/**
 * Server→client broadcast Notice fired when a new
 * [[sigil.storage.StoredFile]] becomes visible to a caller. UIs use
 * this to incrementally update their "files" panel without
 * repolling [[RequestStoredFileList]].
 */
case class StoredFileCreated(file: StoredFileSummary) extends Notice derives RW
