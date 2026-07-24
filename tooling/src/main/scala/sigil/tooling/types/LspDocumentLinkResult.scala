package sigil.tooling.types

import fabric.rw.*

case class LspDocumentLinkResult(filePath: String, items: List[LspDocumentLinkItem]) extends sigil.tool.ToolOutput derives RW
