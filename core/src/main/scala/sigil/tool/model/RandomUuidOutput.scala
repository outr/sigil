package sigil.tool.model

import fabric.rw.*

case class RandomUuidOutput(uuid: String) extends sigil.tool.ToolOutput derives RW
