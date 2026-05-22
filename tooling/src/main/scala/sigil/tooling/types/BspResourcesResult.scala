package sigil.tooling.types

import fabric.rw.*

case class BspResourcesResult(projectRoot: String, items: List[BspTargetResources]) extends sigil.tool.ToolOutput derives RW
