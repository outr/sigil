package sigil.tooling.types

import fabric.rw.*

case class BspDependencySourcesResult(projectRoot: String,
                                      items: List[BspTargetDependencySources],
                                      error: Option[String] = None)
  extends sigil.tool.ToolOutput derives RW
