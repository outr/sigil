package sigil.tooling.types

import fabric.rw.*

/** Result of `lsp_apply_code_action`. Sum type so the agent can
  * pattern-match on whether the cache had an action at the requested
  * index, whether application succeeded, etc. */
enum LspApplyCodeActionResult derives RW {
  case Applied(title: String, message: String)
  case CommandExecuted(title: String)
  case Failed(title: String, message: String)
  case CacheEmpty(uri: String)
  case OutOfRange(requested: Int, available: Int)
}
