package sigil.tooling.types

import fabric.rw.*

case class BspInverseSourcesResult(projectRoot: String, filePath: String, targets: List[String]) derives RW
