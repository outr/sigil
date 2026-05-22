package sigil.tooling.types

import fabric.rw.*

case class LspCodeLensResult(filePath: String, items: List[LspCodeLensItem])
  extends sigil.tool.ToolOutput derives RW
