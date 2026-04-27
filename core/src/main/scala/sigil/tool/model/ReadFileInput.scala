package sigil.tool.model

import fabric.rw.*
import sigil.tool.ToolInput

/**
 * Input for `read_file`. `offset` (0-indexed) and `limit` apply
 * line-wise; omitting either reads from the start / to the end.
 */
case class ReadFileInput(filePath: String,
                         offset: Option[Int] = None,
                         limit: Option[Int] = None) extends ToolInput derives RW
