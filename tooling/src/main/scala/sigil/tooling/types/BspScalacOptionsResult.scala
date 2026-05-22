package sigil.tooling.types

import fabric.rw.*

case class BspScalacOptionsResult(projectRoot: String, items: List[BspTargetScalacOptions]) extends sigil.tool.ToolOutput derives RW
