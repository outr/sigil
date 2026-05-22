package sigil.tooling.types

import fabric.rw.*

case class BspSourcesResult(projectRoot: String, items: List[BspTargetSources]) extends sigil.tool.ToolOutput derives RW
