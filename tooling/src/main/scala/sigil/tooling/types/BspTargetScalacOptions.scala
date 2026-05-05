package sigil.tooling.types

import fabric.rw.*

case class BspTargetScalacOptions(target: String,
                                   options: List[String],
                                   classDirectory: Option[String],
                                   classpath: List[String]) derives RW
