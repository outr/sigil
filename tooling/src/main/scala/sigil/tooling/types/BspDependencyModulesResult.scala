package sigil.tooling.types

import fabric.rw.*

case class BspDependencyModulesResult(projectRoot: String, items: List[BspTargetDependencyModules]) extends sigil.tool.ToolOutput derives RW
