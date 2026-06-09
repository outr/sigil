package sigil.tooling.types

import fabric.rw.*

case class BspSourcesResult(projectRoot: String,
                            items: List[BspTargetSources],
                            error: Option[String] = None) extends sigil.tool.ToolOutput derives RW
