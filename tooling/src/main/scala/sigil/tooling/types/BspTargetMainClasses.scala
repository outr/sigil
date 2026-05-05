package sigil.tooling.types

import fabric.rw.*

case class BspTargetMainClasses(target: String, classes: List[BspMainClassEntry]) derives RW
