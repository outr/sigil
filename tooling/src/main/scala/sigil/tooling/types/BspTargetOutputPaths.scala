package sigil.tooling.types

import fabric.rw.*

case class BspTargetOutputPaths(target: String, paths: List[BspOutputPathItem]) derives RW
