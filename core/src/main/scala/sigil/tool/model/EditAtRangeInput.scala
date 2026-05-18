package sigil.tool.model

import fabric.rw.*
import sigil.tool.ToolInput

/**
 * Input for `edit_at_range` — position-based file edit. The range
 * `[startLine:startChar, endLine:endChar)` is half-open: the end
 * position is exclusive. Lines and characters are 0-indexed.
 *
 * `start == end` is a pure insert at that position. `newText == ""`
 * deletes the range. `expectedHash` enables safe-edit: the commit
 * only succeeds if the file's SHA-256 still matches at write time.
 */
case class EditAtRangeInput(filePath: String,
                            startLine: Int,
                            startChar: Int,
                            endLine: Int,
                            endChar: Int,
                            newText: String,
                            expectedHash: Option[String] = None)
  extends ToolInput derives RW
