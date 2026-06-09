package sigil.tooling.types

import fabric.rw.*

case class BspMainClassesResult(projectRoot: String,
                                items: List[BspTargetMainClasses],
                                error: Option[String] = None) extends sigil.tool.ToolOutput derives RW
