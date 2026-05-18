package sigil.tooling.dispatch

import fabric.Json
import rapid.Task
import sigil.TurnContext
import sigil.tool.output.ToolOutputNode

/**
 * Adapter for `lsp_find_references`. The originating tool returns a
 * single typed `LspFindReferencesOutput` value (not a paginated
 * stream), so its persisted rows are flat — one row per location.
 * The default projection emits the raw row payload as the worker
 * item; downstream pipelines can substitute `{{item.filePath}}`,
 * `{{item.range.start.line}}`, etc.
 *
 * Apps that prefer a richer projection (e.g. per-callsite excerpts
 * lifted from the file contents) register their own adapter under
 * the same tool name; the registry's last-write wins so the
 * override sticks.
 */
object LspFindReferencesWorkerItemSourceAdapter extends WorkerItemSourceAdapter {

  override def itemsFor(rows: List[ToolOutputNode], ctx: TurnContext): Task[List[Json]] = Task.pure {
    rows.sortBy(r => (r.level, r.ordinal)).map(_.payload)
  }
}
