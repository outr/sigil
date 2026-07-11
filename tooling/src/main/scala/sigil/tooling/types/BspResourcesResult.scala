package sigil.tooling.types

import fabric.rw.*

case class BspResourcesResult(projectRoot: String,
                              items: List[BspTargetResources],
                              error: Option[String] = None)
  extends sigil.tool.ToolOutput derives RW
