package sigil.tooling.types

import fabric.rw.*

/**
 * Whether the symbol at the requested position can be renamed.
 * `Renameable` carries the editable range; `NotRenameable` is the
 * server's "no" answer.
 */
enum LspPrepareRenameResult extends sigil.tool.ToolOutput derives RW {
  case Renameable(range: LspRange)
  case NotRenameable
}
