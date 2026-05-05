package sigil.tooling.types

import fabric.rw.*

case class BspTargetResources(target: String, resources: List[String]) derives RW
