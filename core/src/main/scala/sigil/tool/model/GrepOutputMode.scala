package sigil.tool.model

import fabric.rw.*

/**
 * How `grep` shapes its (bounded) output — the caller chooses before the
 * search runs, so output is bounded by construction and the model drives
 * the funnel (Claude Code's ripgrep shape). Sigil #346.
 *
 *   - [[FilesWithMatches]] (default) — just the file paths that match;
 *     the cheap scoping pass.
 *   - [[Content]] — the matching lines as `file:line: text`; used once
 *     the file set is narrow.
 *   - [[Count]] — per-file match counts.
 */
enum GrepOutputMode derives RW:
  case FilesWithMatches
  case Content
  case Count
