package sigil.tooling.types

import fabric.rw.*

case class BspResourcesResult(projectRoot: String, items: List[BspTargetResources]) derives RW
