package sigil.tool.model

import fabric.rw.*

/**
 * Typed result for [[sigil.tool.fs.DeleteFileTool]]. `deleted` is
 * true when a file existed and was removed; false when the path
 * didn't exist.
 */
case class DeleteFileOutput(deleted: Boolean) derives RW
