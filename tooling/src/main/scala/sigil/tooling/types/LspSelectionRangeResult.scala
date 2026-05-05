package sigil.tooling.types

import fabric.rw.*

case class LspSelectionRangeResult(filePath: String, chains: List[LspSelectionRangeChain]) derives RW
