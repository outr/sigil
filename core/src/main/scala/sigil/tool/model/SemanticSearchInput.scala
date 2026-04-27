package sigil.tool.model

import fabric.rw.*
import sigil.tool.ToolInput

case class SemanticSearchInput(query: String,
                               topK: Option[Int] = None) extends ToolInput derives RW
