package sigil.signal

import fabric.rw.*

/**
 * Server→client response carrying the full bytes of a stored file.
 * `base64Data` is the file's contents encoded base64 (whatever
 * `contentType` indicates). UIs decode and render per the
 * `file.contentType`; large binaries can be streamed directly to
 * the user via download.
 */
case class StoredFileContent(file: StoredFileSummary, base64Data: String) extends Notice derives RW
