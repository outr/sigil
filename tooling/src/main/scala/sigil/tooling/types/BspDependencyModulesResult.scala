package sigil.tooling.types

import fabric.rw.*

case class BspDependencyModulesResult(projectRoot: String, items: List[BspTargetDependencyModules]) derives RW
