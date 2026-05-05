package sigil.tooling.types

import fabric.rw.*

case class BspMainClassEntry(className: String, arguments: List[String]) derives RW
