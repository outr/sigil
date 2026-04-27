package sigil.tool.model

import fabric.rw.*
import sigil.tool.ToolInput

case class WebSearchInput(query: String,
                          maxResults: Option[Int] = None) extends ToolInput derives RW
