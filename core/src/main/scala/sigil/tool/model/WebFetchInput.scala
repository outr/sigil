package sigil.tool.model

import fabric.rw.*
import sigil.tool.ToolInput

case class WebFetchInput(url: String,
                         maxLength: Option[Int] = None) extends ToolInput derives RW
