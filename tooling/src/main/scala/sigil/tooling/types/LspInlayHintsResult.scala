package sigil.tooling.types

import fabric.rw.*

case class LspInlayHintsResult(filePath: String, items: List[LspInlayHintItem]) derives RW
