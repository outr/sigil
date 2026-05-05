package sigil.tooling.types

import fabric.rw.*

case class BspTargetDependencyModules(target: String, modules: List[BspDependencyModule]) derives RW
