package sigil.tooling.types

import fabric.rw.*

case class LspSelectionRangeResult(filePath: String, chains: List[LspSelectionRangeChain]) extends sigil.tool.ToolOutput derives RW
