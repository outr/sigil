package sigil.tool.model

import fabric.rw.*

/**
 * Output shape for `git_diff`.
 */
enum GitDiffFormat derives RW {

  /** Raw unified-diff text under a `text` field. */
  case Text

  /** A structured hunk array: `{hunks: [{file, oldStart, newStart,
    * lines: [{kind, text}]}]}`. */
  case Hunks
}
