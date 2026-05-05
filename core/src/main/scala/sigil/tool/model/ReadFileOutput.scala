package sigil.tool.model

import fabric.rw.*

/** Typed result for [[sigil.tool.fs.ReadFileTool]]. `hash` is the
  * full-file hash for use as `expectedHash` on a subsequent
  * write/edit; only populated when the read was unwindowed (the
  * hash represents bytes the agent didn't see when partial-read).
  * `linesRead <= totalLines`. */
case class ReadFileOutput(content: String,
                          totalLines: Int,
                          linesRead: Int,
                          hash: Option[String] = None) derives RW
