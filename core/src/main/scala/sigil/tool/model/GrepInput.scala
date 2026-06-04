package sigil.tool.model

import fabric.rw.*
import sigil.tool.ToolInput

/**
 * Input for `grep` — regex search across files, with bounded output
 * modes (Claude Code's ripgrep shape, Sigil #346). `glob` optionally
 * restricts the file set (e.g. `**.scala`); `contextLines` controls
 * surrounding-context output in `Content` mode; `headLimit` caps the
 * number of returned entries.
 *
 * `outputMode` chooses the result shape before the search runs:
 * `FilesWithMatches` (default — paths only), `Content` (the matching
 * lines), or `Count` (per-file counts). To search within a prior
 * result, grep the file(s) that result named — no reference handle.
 *
 * `includeIgnored` opts back into well-known noise directories
 * (build outputs, IDE state, VCS metadata, package-manager caches,
 * `.claude/` worktrees) that are skipped by default — those almost
 * always drown legitimate matches in duplicated or generated content.
 */
case class GrepInput(
  path: String,
  pattern: String,
  glob: Option[String] = None,
  outputMode: GrepOutputMode = GrepOutputMode.FilesWithMatches,
  @description("Maximum number of entries returned (files in FilesWithMatches/Count, lines in Content). Default 100. Over the cap you get the first N plus a note to narrow with a more specific pattern, a glob, or a subpath.")
  headLimit: Int = 100,
  maxMatches: Int = 500,
  contextLines: Int = 0,
  @description("When true, search inside default-excluded noise directories (.git, target, node_modules, .claude/worktrees, .venv, __pycache__, dist, build, .idea, .vscode, .metals, .bloop, etc.). Off by default — those directories almost always contain build artifacts, IDE state, or throwaway clones that drown legitimate matches.")
  includeIgnored: Boolean = false)
  extends ToolInput derives RW
