package sigil.tooling.types

import fabric.rw.*

case class BspMainClassesResult(projectRoot: String, items: List[BspTargetMainClasses]) derives RW
