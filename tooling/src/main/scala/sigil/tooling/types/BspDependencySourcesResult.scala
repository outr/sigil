package sigil.tooling.types

import fabric.rw.*

case class BspDependencySourcesResult(projectRoot: String, items: List[BspTargetDependencySources]) derives RW
