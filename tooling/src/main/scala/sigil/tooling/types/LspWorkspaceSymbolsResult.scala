package sigil.tooling.types

import fabric.rw.*

case class LspWorkspaceSymbolsResult(query: String,
                                      items: List[LspWorkspaceSymbol],
                                      totalCount: Int,
                                      truncated: Boolean) derives RW
