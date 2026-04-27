package sigil.tool.fs

import fabric.rw.*

/**
 * One match from [[GrepTool]]. `contextBefore` / `contextAfter` are
 * lines surrounding the match — empty when context is not requested.
 */
case class GrepMatch(filePath: String,
                     lineNumber: Int,
                     content: String,
                     contextBefore: List[String] = Nil,
                     contextAfter: List[String] = Nil) derives RW
