package sigil.tooling.types

import fabric.rw.*

case class LspCodeActionResult(filePath: String, items: List[LspCodeActionItem]) derives RW
