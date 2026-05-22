package sigil.tooling.types

import fabric.rw.*

case class BspTestClassesResult(projectRoot: String, items: List[BspTargetTestClasses]) extends sigil.tool.ToolOutput derives RW
