package sigil.signal

import fabric.rw.*

/**
 * Server→client snapshot of the caller-accessible stored-file list,
 * sent in response to [[RequestStoredFileList]] or unsolicited when
 * the framework wants to refresh an open files panel.
 */
case class StoredFileListSnapshot(files: List[StoredFileSummary]) extends Notice derives RW
