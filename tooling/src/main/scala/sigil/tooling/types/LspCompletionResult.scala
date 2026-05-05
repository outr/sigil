package sigil.tooling.types

import fabric.rw.*

case class LspCompletionResult(filePath: String,
                                items: List[LspCompletionItem],
                                totalCount: Int,
                                truncated: Boolean) derives RW
