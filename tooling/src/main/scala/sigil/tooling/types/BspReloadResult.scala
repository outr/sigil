package sigil.tooling.types

import fabric.rw.*

case class BspReloadResult(projectRoot: String,
                           error: Option[String] = None)
  extends sigil.tool.ToolOutput derives RW
