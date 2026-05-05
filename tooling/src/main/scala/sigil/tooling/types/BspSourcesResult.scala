package sigil.tooling.types

import fabric.rw.*

case class BspSourcesResult(projectRoot: String, items: List[BspTargetSources]) derives RW
