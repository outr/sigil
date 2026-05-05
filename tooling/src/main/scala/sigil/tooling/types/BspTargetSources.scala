package sigil.tooling.types

import fabric.rw.*

case class BspTargetSources(target: String, sources: List[BspSourceItem]) derives RW
