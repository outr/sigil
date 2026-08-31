package sigil.tool.model

import fabric.rw.*

/**
 * The role a single line plays inside a unified-diff hunk.
 */
enum GitDiffLineKind derives RW {

  /**
   * Unchanged context line shown around the edit.
   */
  case Context

  /**
   * Line added in the new revision.
   */
  case Add

  /**
   * Line removed from the old revision.
   */
  case Remove
}
