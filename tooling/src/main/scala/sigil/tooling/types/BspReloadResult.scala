package sigil.tooling.types

import fabric.rw.*

case class BspReloadResult(projectRoot: String) extends sigil.tool.ToolOutput derives RW
