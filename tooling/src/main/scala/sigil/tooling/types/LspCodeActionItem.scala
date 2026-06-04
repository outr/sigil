package sigil.tooling.types

import fabric.rw.*

/**
 * One code-action listing entry. `kind` is the LSP code-action kind
 * ("quickfix", "refactor", etc.) for [[org.eclipse.lsp4j.CodeAction]]
 * results, or "command" for legacy [[org.eclipse.lsp4j.Command]]
 * results. `index` is the cache index — pass it back to
 * `lsp_apply_code_action`.
 */
case class LspCodeActionItem(index: Int, kind: String, title: String) derives RW
