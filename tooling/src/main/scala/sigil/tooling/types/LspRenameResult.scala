package sigil.tooling.types

import fabric.rw.*

/** Result of `lsp_rename`. `Applied` reports clean success across
  * `filesChanged` files; `PartialFailure` means the server returned
  * edits but at least one file's apply failed; `NoEdits` is the
  * server's "I have no rename for this position" answer. */
enum LspRenameResult derives RW {
  case Applied(newName: String, filesChanged: Int)
  case PartialFailure(newName: String, filesChanged: Int)
  case NoEdits
}
