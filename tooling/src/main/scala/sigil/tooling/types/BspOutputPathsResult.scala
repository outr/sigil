package sigil.tooling.types

import fabric.rw.*

case class BspOutputPathsResult(projectRoot: String,
                                items: List[BspTargetOutputPaths],
                                error: Option[String] = None)
  extends sigil.tool.ToolOutput derives RW
