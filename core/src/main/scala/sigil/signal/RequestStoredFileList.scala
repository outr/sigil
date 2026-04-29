package sigil.signal

import fabric.rw.*
import sigil.SpaceId

/**
 * Client→server pull for the stored-file list. The server replies
 * with a [[StoredFileListSnapshot]] filtered by the optional
 * `spaces` set — `None` means "every space the caller is
 * authorized to see" via `Sigil.accessibleSpaces`.
 *
 * Apps' UIs typically issue this on connect / on a "files panel
 * open" event; the response Notice carries the typed summary
 * vocabulary the Dart codegen + downstream renderers share.
 */
case class RequestStoredFileList(spaces: Option[Set[SpaceId]] = None) extends Notice derives RW
