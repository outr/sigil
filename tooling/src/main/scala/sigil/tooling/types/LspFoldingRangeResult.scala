package sigil.tooling.types

import fabric.rw.*

case class LspFoldingRangeResult(filePath: String, ranges: List[LspFoldingRangeItem])
  extends sigil.tool.ToolOutput derives RW
