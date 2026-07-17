package sigil.tooling.types

import fabric.rw.*

case class LspCodeActionResult(filePath: String, items: List[LspCodeActionItem]) extends sigil.tool.ToolOutput derives RW
