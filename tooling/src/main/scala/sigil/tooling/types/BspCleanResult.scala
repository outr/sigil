package sigil.tooling.types

import fabric.rw.*

case class BspCleanResult(projectRoot: String, targetCount: Int, cleaned: Boolean) derives RW
