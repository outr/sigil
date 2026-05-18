package sigil.tooling.dispatch

import fabric.Json
import rapid.Task
import sigil.TurnContext
import sigil.tool.output.ToolOutputNode

/**
 * Adapter for `lsp_workspace_symbols`. The originating tool's
 * paginated output is a flat list of [[sigil.tooling.types.LspWorkspaceSymbol]]
 * hits; one row becomes one worker item. Useful for "per-symbol
 * cross-reference + edit" flows.
 */
object LspWorkspaceSymbolsWorkerItemSourceAdapter extends WorkerItemSourceAdapter {

  override def itemsFor(rows: List[ToolOutputNode], ctx: TurnContext): Task[List[Json]] = Task.pure {
    rows.sortBy(r => (r.level, r.ordinal)).map(_.payload)
  }
}
