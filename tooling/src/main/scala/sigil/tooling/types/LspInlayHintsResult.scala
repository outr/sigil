package sigil.tooling.types

import fabric.rw.*

case class LspInlayHintsResult(filePath: String, items: List[LspInlayHintItem])
  extends sigil.tool.ToolOutput derives RW
