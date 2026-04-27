package sigil.tool.model

import fabric.rw.*
import sigil.tool.ToolInput

case class DeleteFileInput(filePath: String) extends ToolInput derives RW
