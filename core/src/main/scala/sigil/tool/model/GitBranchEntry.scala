package sigil.tool.model

import fabric.rw.*

/**
 * One branch row in a [[GitBranchOutput]]. `name` is the branch name
 * (the `remotes/` prefix stripped for remote-tracking refs); `sha`
 * the abbreviated tip commit; `isCurrent` marks the checked-out
 * branch; `isRemote` distinguishes remote-tracking refs; `tracking`
 * carries the `[ahead/behind]` tracking annotation when git emits one.
 */
case class GitBranchEntry(name: String,
                          sha: String,
                          isCurrent: Boolean,
                          isRemote: Boolean,
                          tracking: Option[String] = None) derives RW
