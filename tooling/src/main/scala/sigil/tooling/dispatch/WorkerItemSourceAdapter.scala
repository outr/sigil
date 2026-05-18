package sigil.tooling.dispatch

import fabric.Json
import rapid.Task
import sigil.TurnContext

/**
 * Projects a prior tool call's persisted paginated output into the
 * `List[Json]` worker-item shape [[DispatchWorkersTool]] consumes.
 * Apps wire one [[WorkerItemSourceAdapter]] per tool whose output
 * shape they want to feed into dispatch.
 *
 * The framework looks up the adapter by tool name at execute time
 * via [[WorkerItemSourceAdapter.registry]]. Unregistered tool names
 * surface a structured error (the adapter is missing — agents
 * shouldn't ship the wrong call); the agent's next iteration can
 * either pick a different source or report the gap to the user.
 *
 * Adapters receive the originating tool-call's persisted rows
 * (already filtered by `(conversationId, callId)`) and return the
 * projected worker items in the order the dispatcher should run
 * them. The `groupBy` policy is applied AFTER the adapter — the
 * adapter just exposes the raw row→item projection.
 */
trait WorkerItemSourceAdapter {

  /** Project the originating tool call's persisted output rows into
    * worker items. Rows arrive in `(level, ordinal)` order. */
  def itemsFor(rows: List[sigil.tool.output.ToolOutputNode],
               ctx: TurnContext): Task[List[Json]]
}

object WorkerItemSourceAdapter {

  private val registry: scala.collection.concurrent.Map[String, WorkerItemSourceAdapter] =
    scala.collection.concurrent.TrieMap.empty

  /** Register an adapter for a tool's name. Idempotent — re-registering
    * the same name overwrites the prior adapter. */
  def register(toolName: String, adapter: WorkerItemSourceAdapter): Unit = {
    registry.put(toolName, adapter)
    ()
  }

  /** Lookup the registered adapter for `toolName`, or `None`. */
  def lookup(toolName: String): Option[WorkerItemSourceAdapter] =
    registry.get(toolName)

  /** Register the framework-shipped adapters for the tools whose
    * outputs naturally compose with `dispatch_workers` — `grep`,
    * `lsp_find_references`, `lsp_workspace_symbols`. Idempotent;
    * called from [[sigil.tooling.ToolingSigil]] at boot. Apps may
    * also register their own adapters at any time. */
  def registerShipped(): Unit = {
    register("grep", GrepWorkerItemSourceAdapter)
    register("lsp_find_references", LspFindReferencesWorkerItemSourceAdapter)
    register("lsp_workspace_symbols", LspWorkspaceSymbolsWorkerItemSourceAdapter)
  }
}
