package sigil.tool.model

import fabric.rw.*
import sigil.tool.ToolInput

/**
 * Input for `grep` — regex search across files. `glob` optionally
 * restricts the file set (e.g. `**.scala`); `contextLines` controls
 * surrounding-context output.
 *
 * `includeIgnored` opts back into well-known noise directories
 * (build outputs, IDE state, VCS metadata, package-manager caches,
 * `.claude/` worktrees) that are skipped by default — those almost
 * always drown legitimate matches in duplicated or generated content.
 *
 * `from` composes searches: pass a prior `grep` / `glob` result's
 * `callId` to restrict this search to the files that output surfaced.
 */
case class GrepInput(path: String,
                     pattern: String,
                     glob: Option[String] = None,
                     maxMatches: Int = 500,
                     contextLines: Int = 0,
                     @description("When true, search inside default-excluded noise directories (.git, target, node_modules, .claude/worktrees, .venv, __pycache__, dist, build, .idea, .vscode, .metals, .bloop, etc.). Off by default — those directories almost always contain build artifacts, IDE state, or throwaway clones that drown legitimate matches.")
                     includeIgnored: Boolean = false,
                     @description("Optional reference to a prior grep or glob result — its `callId` (echoed on that result). When set, this search is restricted to the files that prior output surfaced, so you can narrow a file set first and then search within it.")
                     from: Option[String] = None) extends ToolInput derives RW
