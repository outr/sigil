package sigil.tooling.types

import fabric.rw.*

case class LspCodeLensResult(filePath: String, items: List[LspCodeLensItem]) derives RW
