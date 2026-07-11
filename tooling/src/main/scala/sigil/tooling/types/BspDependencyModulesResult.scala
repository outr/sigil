package sigil.tooling.types

import fabric.rw.*

case class BspDependencyModulesResult(projectRoot: String,
                                      items: List[BspTargetDependencyModules],
                                      error: Option[String] = None)
  extends sigil.tool.ToolOutput derives RW
