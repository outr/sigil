package sigil.tooling.types

import fabric.rw.*

case class BspTargetTestClasses(target: String,
                                framework: Option[String],
                                classes: List[String])
  derives RW
