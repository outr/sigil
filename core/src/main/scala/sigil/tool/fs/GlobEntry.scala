package sigil.tool.fs

import fabric.rw.*

/**
 * One entry in the paginated `glob` output — a single matching file path.
 */
case class GlobEntry(path: String) derives RW
