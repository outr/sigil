package sigil.tooling.types

import fabric.rw.*

case class BspTestClassesResult(projectRoot: String,
                                items: List[BspTargetTestClasses],
                                error: Option[String] = None)
  extends sigil.tool.ToolOutput derives RW
