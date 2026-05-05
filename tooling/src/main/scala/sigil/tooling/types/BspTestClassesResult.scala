package sigil.tooling.types

import fabric.rw.*

case class BspTestClassesResult(projectRoot: String, items: List[BspTargetTestClasses]) derives RW
