package sigil.tool.model

import fabric.rw.*

/**
 * A single git porcelain status code, occupying one column of the
 * two-character `XY` prefix in `git status --porcelain=v1`. `X` is
 * the index (staged) state, `Y` is the working-tree state.
 */
enum GitFileState derives RW {

  /**
   * No change in this column (porcelain space).
   */
  case Unmodified

  /**
   * Modified.
   */
  case Modified

  /**
   * Added to the index.
   */
  case Added

  /**
   * Deleted.
   */
  case Deleted

  /**
   * Renamed.
   */
  case Renamed

  /**
   * Copied.
   */
  case Copied

  /**
   * Updated but unmerged (conflict).
   */
  case Unmerged

  /**
   * Untracked — not tracked by git.
   */
  case Untracked

  /**
   * Ignored by `.gitignore`.
   */
  case Ignored
}

object GitFileState {

  /**
   * Map a single porcelain status character to its typed state.
   * Unknown characters fall back to [[Unmodified]] — the parser is
   * lenient because git can emit codes the schema doesn't enumerate
   * (e.g. type-change `T`).
   */
  def fromChar(c: String): GitFileState = c match {
    case " " => Unmodified
    case "M" => Modified
    case "A" => Added
    case "D" => Deleted
    case "R" => Renamed
    case "C" => Copied
    case "U" => Unmerged
    case "?" => Untracked
    case "!" => Ignored
    case _ => Unmodified
  }
}
