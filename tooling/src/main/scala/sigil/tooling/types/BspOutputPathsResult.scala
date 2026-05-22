package sigil.tooling.types

import fabric.rw.*

case class BspOutputPathsResult(projectRoot: String, items: List[BspTargetOutputPaths]) extends sigil.tool.ToolOutput derives RW
