package sigil.tool.model

import fabric.rw.*

/**
 * One tracked or untracked entry in a [[GitStatusOutput]]. `path` is
 * the entry's path relative to the repo root; `indexState` /
 * `workingState` are the two porcelain columns; `renamedFrom` is the
 * original path for rename / copy entries.
 */
case class GitStatusEntry(path: String,
                          indexState: GitFileState,
                          workingState: GitFileState,
                          renamedFrom: Option[String] = None)
  derives RW
