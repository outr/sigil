package sigil.tooling.types

import fabric.rw.*

case class BspListTargetsResult(projectRoot: String, targets: List[BspBuildTarget]) extends sigil.tool.ToolOutput derives RW
