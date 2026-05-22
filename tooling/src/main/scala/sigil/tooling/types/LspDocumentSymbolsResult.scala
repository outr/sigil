package sigil.tooling.types

import fabric.rw.*

case class LspDocumentSymbolsResult(filePath: String, entries: List[LspDocumentSymbolEntry])
  extends sigil.tool.ToolOutput derives RW
