package sigil.tool.model

import fabric.rw.*
import sigil.tool.ToolInput

case class WriteFileInput(filePath: String, content: String) extends ToolInput derives RW
