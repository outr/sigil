package sigil.tooling.types

import fabric.rw.*

case class BspTargetDependencySources(target: String, sources: List[String]) derives RW
