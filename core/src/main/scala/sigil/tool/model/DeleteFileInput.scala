package sigil.tool.model

import fabric.rw.*
import sigil.tool.ToolInput

case class DeleteFileInput(path: String) extends ToolInput derives RW
