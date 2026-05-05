package sigil.tooling.types

import fabric.rw.*

case class LspDocumentLinkResult(filePath: String, items: List[LspDocumentLinkItem]) derives RW
