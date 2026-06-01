package sigil.tool.model

import fabric.rw.*
import sigil.tool.ToolInput

case class GlobInput(basePath: String,
                     pattern: String,
                     maxResults: Int = 1000) extends ToolInput derives RW
