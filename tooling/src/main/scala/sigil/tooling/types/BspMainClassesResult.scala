package sigil.tooling.types

import fabric.rw.*

case class BspMainClassesResult(projectRoot: String, items: List[BspTargetMainClasses]) extends sigil.tool.ToolOutput derives RW
