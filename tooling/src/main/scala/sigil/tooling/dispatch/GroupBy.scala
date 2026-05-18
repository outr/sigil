package sigil.tooling.dispatch

import fabric.rw.*

/**
 * Item-grouping policy applied when projecting a prior tool call's
 * paginated output into [[WorkerItemSource.FromCall]]'s `List[Json]`.
 *
 *   - [[GroupBy.None]] — one worker item per persisted row (paginated
 *     node). Default. Useful when each `grep` line, each
 *     `lsp_find_references` location, etc. naturally maps to one
 *     worker invocation.
 *   - [[GroupBy.ByKey]] — group rows by the named top-level JSON key
 *     and emit one item per group, with the children listed under
 *     `items`. Useful for "one worker per file" shapes when grep
 *     returned per-line matches but the agent wants the file-level
 *     aggregate as the worker boundary.
 *   - [[GroupBy.TopLevelOnly]] — only emit rows at `level == 0`
 *     (skip children of paginated tree nodes). Useful when the
 *     prior tool produced a parent/child tree and the worker should
 *     run once per parent — `grep`'s file-level FileMatch entries
 *     are the canonical example.
 */
enum GroupBy derives RW {
  case None
  case ByKey(key: String)
  case TopLevelOnly
}
