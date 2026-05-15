package sigil.tool.model

import fabric.rw.*

/**
 * Typed result for `edit_at_range`. Success carries the post-edit
 * SHA-256 plus line-count and byte-count deltas so the agent can
 * verify the change shape without re-reading the file.
 */
enum EditAtRangeOutput derives RW {
  case Success(hash: Option[String], lineDelta: Int, byteDelta: Int)
}
