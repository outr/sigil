package sigil.tooling.types

import fabric.rw.*

case class BspListTargetsResult(projectRoot: String,
                                targets: List[BspBuildTarget],
                                error: Option[String] = None) extends sigil.tool.ToolOutput derives RW
