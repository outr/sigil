package sigil.tool.model

import fabric.rw.*
import sigil.tool.ToolInput

/**
 * Input for `git_log` — recent commit history. `path` filters to
 * commits touching that path; `since` is any git-date expression
 * (`"2 weeks ago"`, `"2026-04-01"`); `limit` defaults to 20.
 * `includeBody = true` returns the full commit body alongside the
 * subject (useful for short surveys; off by default to keep the
 * payload tight).
 */
case class GitLogInput(path: Option[String] = None,
                       since: Option[String] = None,
                       limit: Option[Int] = None,
                       includeBody: Boolean = false,
                       workingDir: Option[String] = None) extends ToolInput derives RW
