package sigil.provider

import fabric.rw.*

case class Instructions(system: String, developer: Option[String]) derives RW
