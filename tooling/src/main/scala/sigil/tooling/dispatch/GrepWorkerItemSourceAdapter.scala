package sigil.tooling.dispatch

import fabric.Json
import rapid.Task
import sigil.TurnContext
import sigil.tool.output.ToolOutputNode

/**
 * Adapter for `grep`'s paginated output. The originating tool
 * emitted a tree: top-level [[sigil.tool.fs.GrepNode.FileMatch]]
 * nodes with per-file [[sigil.tool.fs.GrepNode.LineMatch]] children.
 *
 * Default projection: emit one worker item per top-level node (one
 * worker per file with matches). The item shape is
 * `{filePath, matchCount}`. Callers wanting per-line items pass
 * `GroupBy.None` and the dispatcher emits one item per persisted
 * row instead.
 */
object GrepWorkerItemSourceAdapter extends WorkerItemSourceAdapter {

  override def itemsFor(rows: List[ToolOutputNode], ctx: TurnContext): Task[List[Json]] = Task.pure {
    rows.sortBy(r => (r.level, r.ordinal)).map(_.payload)
  }
}
